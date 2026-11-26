package com.investment.dashboard.manual

import com.investment.dashboard.TestcontainersConfiguration
import com.investment.dashboard.dividend.ProfileSource
import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.LedgerEntryType
import com.investment.dashboard.ledger.LedgerSource
import com.investment.dashboard.support.ManualApiTestSupport
import com.investment.dashboard.support.TestClockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
@WithMockUser
class ManualEntryApiTest : ManualApiTestSupport() {

    @Test
    @DisplayName("수동 배당을 넣으면 201 과 그 id 의 MANUAL 원화 배당 행을 준다")
    fun manual_dividend_returns_id_of_manual_krw_dividend_row() {
        val isaId = createIsa()

        val id = postDividend(isaId, "458730", "2026-10-31", "30000")

        val entries = ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)
        assertThat(entries).hasSize(1)
        val dividend = entries.first()
        assertThat(dividend.id).isEqualTo(id)
        assertThat(dividend.type).isEqualTo(LedgerEntryType.DIVIDEND)
        assertThat(dividend.source).isEqualTo(LedgerSource.MANUAL)
        assertThat(dividend.currency).isEqualTo("KRW")
        assertThat(dividend.symbol).isEqualTo("458730")
        assertThat(dividend.settlementDate).isEqualTo(LocalDate.parse("2026-10-31"))
        assertThat(dividend.amount).isEqualByComparingTo("30000")
        assertThat(dividend.netAmount).isEqualByComparingTo("30000")
        assertThat(dividend.tax).isEqualByComparingTo("0")
    }

    @Test
    @DisplayName("수동 배당을 넣으면 주당 분배금이 입금액 ÷ 보유 수량으로 바뀐다")
    fun manual_dividend_updates_per_share() {
        val isaId = createIsa()
        putHolding(isaId, "458730", "200")
        putProfile(isaId, "458730", "120", listOf(1, 4, 7, 10))

        postDividend(isaId, "458730", "2026-10-31", "30000")

        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")!!.perShare)
            .isEqualByComparingTo("150.000000")
    }

    @Test
    @DisplayName("입금액이 보유 수량으로 나누어떨어지지 않으면 주당 분배금은 소수 6자리로 반올림한다")
    fun manual_dividend_per_share_is_rounded_half_up_to_six_decimals() {
        val isaId = createIsa()
        putHolding(isaId, "458730", "3")
        putProfile(isaId, "458730", "120", listOf(1, 4, 7, 10))

        postDividend(isaId, "458730", "2026-10-31", "20000")

        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")?.perShare)
            .isEqualByComparingTo("6666.666667")
    }

    @Test
    @DisplayName("보유 수량이 0 인 종목에 수동 배당을 넣으면 입금만 저장되고 주당 분배금은 그대로다")
    fun manual_dividend_with_zero_quantity_keeps_profile() {
        val isaId = createIsa()
        putHolding(isaId, "458730", "0")
        putProfile(isaId, "458730", "120", listOf(1, 4, 7, 10))

        mvc.post("/api/accounts/$isaId/dividends") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"458730","date":"2026-10-31","amount":30000}"""
        }.andExpect { status { isCreated() } }

        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")!!.perShare).isEqualByComparingTo("120")
        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)).hasSize(1)
    }

    @Test
    @DisplayName("보유 종목에 없는 종목에 수동 배당을 넣으면 입금만 저장되고 배당 정보는 만들지 않는다")
    fun manual_dividend_without_holding_creates_no_profile() {
        val isaId = createIsa()

        postDividend(isaId, "458730", "2026-10-31", "30000")

        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")).isNull()
        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)).hasSize(1)
    }

    @Test
    @DisplayName("배당 정보가 없는 보유 종목에 수동 배당을 넣으면 그 달을 지급 월로 배당 정보가 생긴다")
    fun manual_dividend_creates_profile_paying_in_its_month() {
        val isaId = createIsa()
        putHolding(isaId, "458730", "200")

        postDividend(isaId, "458730", "2026-10-31", "30000")

        val profile = profileRepository.findByAccountIdAndSymbol(isaId, "458730")
        assertThat(profile?.perShare).isEqualByComparingTo("150")
        assertThat(profile?.payMonths).isEqualTo("10")
        assertThat(profile?.currency).isEqualTo("KRW")
        assertThat(profile?.netRatio).isEqualByComparingTo("1")
        assertThat(profile?.source).isEqualTo(ProfileSource.MANUAL)
    }

    @Test
    @DisplayName("더 늦은 입금이 있는 종목에 지난 입금을 넣으면 주당 분배금은 그대로다")
    fun older_manual_dividend_keeps_per_share_from_newer_one() {
        val isaId = createIsa()
        putHolding(isaId, "458730", "100")
        putProfile(isaId, "458730", "120", listOf(1, 4, 7, 10))
        postDividend(isaId, "458730", "2026-10-31", "15000")

        postDividend(isaId, "458730", "2026-07-31", "12000")

        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")?.perShare).isEqualByComparingTo("150")
    }

    @Test
    @DisplayName("더 늦은 입금이 다른 종목이면 지난 입금으로도 주당 분배금이 바뀐다")
    fun later_dividend_of_other_symbol_does_not_block_per_share_update() {
        val isaId = createIsa()
        putHolding(isaId, "458730", "100")
        putProfile(isaId, "458730", "120", listOf(1, 4, 7, 10))
        postDividend(isaId, "999990", "2026-10-31", "5000")

        postDividend(isaId, "458730", "2026-07-31", "15000")

        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")?.perShare).isEqualByComparingTo("150")
    }

    @Test
    @DisplayName("같은 종목의 더 늦은 행이 배당이 아니면 지난 입금으로도 주당 분배금이 바뀐다")
    fun later_non_dividend_row_of_same_symbol_does_not_block_per_share_update() {
        val isaId = createIsa()
        putHolding(isaId, "458730", "100")
        putProfile(isaId, "458730", "120", listOf(1, 4, 7, 10))
        val buy = LedgerEntry(isaId, LedgerEntryType.BUY, "KRW", LocalDate.parse("2026-10-31"), LedgerSource.MANUAL)
        buy.symbol = "458730"
        buy.quantity = BigDecimal("10")
        buy.amount = BigDecimal("100000")
        ledgerRepository.save(buy)

        postDividend(isaId, "458730", "2026-07-31", "15000")

        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")?.perShare).isEqualByComparingTo("150")
    }

    @Test
    @DisplayName("수동 배당으로 주당 분배금이 바뀌면 배당 정보 갱신 시각이 지금이 된다")
    fun manual_dividend_sets_profile_updated_at_to_now() {
        val isaId = createIsa()
        putHolding(isaId, "458730", "200")
        putProfile(isaId, "458730", "120", listOf(1, 4, 7, 10))
        clock.set(OffsetDateTime.parse("2026-11-23T09:30+09:00").toInstant())

        postDividend(isaId, "458730", "2026-10-31", "30000")

        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")?.updatedAt)
            .isAtSameInstantAs(OffsetDateTime.parse("2026-11-23T09:30+09:00"))
    }

    @Test
    @DisplayName("토스 계좌에 수동 배당을 넣으면 409 MANUAL_NOT_ALLOWED 다")
    fun manual_dividend_on_toss_account_is_409() {
        val tossId = createTossAccount()

        mvc.post("/api/accounts/$tossId/dividends") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"SCHD","date":"2026-10-31","amount":30}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("MANUAL_NOT_ALLOWED") }
        }

        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(tossId)).isEmpty()
    }

    @Test
    @DisplayName("배당 금액이 0 이면 400 이다")
    fun zero_dividend_amount_is_400() {
        val isaId = createIsa()

        val result = mvc.post("/api/accounts/$isaId/dividends") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"458730","date":"2026-10-31","amount":0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("amount")
        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)).isEmpty()
    }

    @Test
    @DisplayName("배당 금액의 소수가 4자리를 넘으면 400 이다")
    fun dividend_amount_with_more_than_four_decimals_is_400() {
        val isaId = createIsa()

        val result = mvc.post("/api/accounts/$isaId/dividends") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"458730","date":"2026-10-31","amount":30000.12345}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("amount")
        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)).isEmpty()
    }

    @Test
    @DisplayName("배당 종목 코드가 20자를 넘으면 400 이다")
    fun dividend_symbol_longer_than_20_is_400() {
        val isaId = createIsa()

        val result = mvc.post("/api/accounts/$isaId/dividends") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"ABCDEFGHIJKLMNOPQRSTU","date":"2026-10-31","amount":30000}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("symbol")
        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)).isEmpty()
    }

    @Test
    @DisplayName("배당 종목 코드가 공백이면 400 이다")
    fun blank_dividend_symbol_is_400() {
        val isaId = createIsa()

        val result = mvc.post("/api/accounts/$isaId/dividends") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":" ","date":"2026-10-31","amount":30000}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("symbol")
        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)).isEmpty()
    }

    @Test
    @DisplayName("배당 날짜가 오늘 이후면 400 이다")
    fun future_dividend_date_is_400() {
        val isaId = createIsa()

        val result = mvc.post("/api/accounts/$isaId/dividends") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"458730","date":"2026-11-23","amount":30000}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("date")
        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)).isEmpty()
    }

    @Test
    @DisplayName("오늘 날짜 배당은 저장된다")
    fun dividend_dated_today_is_saved() {
        val isaId = createIsa()

        postDividend(isaId, "458730", "2026-11-22", "30000")

        val entries = ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)
        assertThat(entries).hasSize(1)
        assertThat(entries.first().settlementDate).isEqualTo(LocalDate.parse("2026-11-22"))
    }

    @Test
    @DisplayName("없는 계좌에 수동 배당을 넣으면 404 NOT_FOUND 다")
    fun manual_dividend_on_unknown_account_is_404() {
        mvc.post("/api/accounts/999999/dividends") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"458730","date":"2026-10-31","amount":30000}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    @DisplayName("배당 날짜가 없으면 400 이고 date 를 알려 준다")
    fun manual_dividend_without_date_is_400_with_date_field() {
        val isaId = createIsa()

        val result = mvc.post("/api/accounts/$isaId/dividends") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"458730","amount":30000}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("date")
        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)).isEmpty()
    }

    @Test
    @DisplayName("수동 입금을 넣으면 201 과 그 id 의 MANUAL 원화 입금 행을 준다")
    fun deposit_returns_id_of_manual_krw_deposit_row() {
        val isaId = createIsa()

        val result = mvc.post("/api/accounts/$isaId/deposits") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"date":"2026-11-10","amount":500000}"""
        }.andExpect { status { isCreated() } }.andReturn()

        val entries = ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)
        assertThat(entries).hasSize(1)
        val deposit = entries.first()
        assertThat(deposit.id).isEqualTo(idOf(result))
        assertThat(deposit.type).isEqualTo(LedgerEntryType.DEPOSIT)
        assertThat(deposit.source).isEqualTo(LedgerSource.MANUAL)
        assertThat(deposit.currency).isEqualTo("KRW")
        assertThat(deposit.symbol).isNull()
        assertThat(deposit.settlementDate).isEqualTo(LocalDate.parse("2026-11-10"))
        assertThat(deposit.amount).isEqualByComparingTo("500000")
        assertThat(deposit.netAmount).isEqualByComparingTo("500000")
        assertThat(deposit.tax).isEqualByComparingTo("0")
    }

    @Test
    @DisplayName("입금 날짜가 오늘 이후면 400 이다")
    fun future_deposit_date_is_400() {
        val isaId = createIsa()

        val result = mvc.post("/api/accounts/$isaId/deposits") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"date":"2026-11-23","amount":500000}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("date")
        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)).isEmpty()
    }

    @Test
    @DisplayName("오늘 날짜 입금은 저장된다")
    fun deposit_dated_today_is_saved() {
        val isaId = createIsa()

        mvc.post("/api/accounts/$isaId/deposits") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"date":"2026-11-22","amount":500000}"""
        }.andExpect { status { isCreated() } }

        val entries = ledgerRepository.findByAccountIdOrderBySettlementDateAsc(isaId)
        assertThat(entries).hasSize(1)
        assertThat(entries.first().settlementDate).isEqualTo(LocalDate.parse("2026-11-22"))
    }

    @Test
    @DisplayName("토스 계좌에 수동 입금을 넣으면 409 MANUAL_NOT_ALLOWED 다")
    fun deposit_on_toss_account_is_409() {
        val tossId = createTossAccount()

        mvc.post("/api/accounts/$tossId/deposits") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"date":"2026-11-10","amount":500000}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("MANUAL_NOT_ALLOWED") }
        }

        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(tossId)).isEmpty()
    }

    @Test
    @DisplayName("없는 계좌에 입금을 넣으면 404 NOT_FOUND 다")
    fun deposit_on_unknown_account_is_404() {
        mvc.post("/api/accounts/999999/deposits") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"date":"2026-11-10","amount":500000}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    @DisplayName("확인 대기 목록에는 이번 달까지 지급 월인데 입금이 없는 종목만 나온다")
    fun pending_dividends_list_only_pay_months_without_dividend() {
        val isaId = createIsa()
        putHolding(isaId, "458730", "100")
        putProfile(isaId, "458730", "120", listOf(1, 4, 7, 10))
        postDividend(isaId, "458730", "2026-01-30", "12000")
        postDividend(isaId, "458730", "2026-04-30", "12000")
        postDividend(isaId, "458730", "2026-07-31", "12000")

        mvc.get("/api/accounts/$isaId/pending-dividends").andExpect {
            status { isOk() }
            content {
                json("""[{"symbol":"458730","month":"2026-10","expectedKrw":12000}]""", JsonCompareMode.STRICT)
            }
        }
    }

    @Test
    @DisplayName("확인 대기 목록은 올해 1월부터 이번 달까지만 나온다")
    fun pending_dividends_cover_january_to_this_month() {
        val isaId = createIsa()
        putHolding(isaId, "458730", "100")
        putProfile(isaId, "458730", "120", listOf(1, 11, 12))

        mvc.get("/api/accounts/$isaId/pending-dividends").andExpect {
            status { isOk() }
            content {
                json(
                    """[{"symbol":"458730","month":"2026-01","expectedKrw":12000},
                        |{"symbol":"458730","month":"2026-11","expectedKrw":12000}]
                    """.trimMargin(),
                    JsonCompareMode.STRICT,
                )
            }
        }
    }

    @Test
    @DisplayName("없는 계좌의 확인 대기 목록은 404 NOT_FOUND 다")
    fun pending_dividends_of_unknown_account_is_404() {
        mvc.get("/api/accounts/999999/pending-dividends").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    @DisplayName("토스 원장 행은 지울 수 없다")
    fun toss_ledger_entry_cannot_be_deleted() {
        val tossId = createTossAccount()
        val tossEntry =
            LedgerEntry(tossId, LedgerEntryType.DEPOSIT, "KRW", LocalDate.parse("2026-10-15"), LedgerSource.TOSS)
        tossEntry.amount = BigDecimal("100000")
        val tossEntryId = ledgerRepository.save(tossEntry).id!!

        mvc.delete("/api/ledger-entries/$tossEntryId") {
            with(csrf())
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("MANUAL_NOT_ALLOWED") }
        }

        assertThat(ledgerRepository.findByIdOrNull(tossEntryId)).isNotNull()
    }

    @Test
    @DisplayName("수동 원장 행을 지우면 204 이고 행이 사라진다")
    fun manual_ledger_entry_is_deleted() {
        val isaId = createIsa()
        val id = postDividend(isaId, "458730", "2026-10-31", "30000")

        mvc.delete("/api/ledger-entries/$id") {
            with(csrf())
        }.andExpect { status { isNoContent() } }

        assertThat(ledgerRepository.findByIdOrNull(id)).isNull()
    }

    @Test
    @DisplayName("없는 원장 행을 지우면 404 NOT_FOUND 다")
    fun delete_unknown_ledger_entry_is_404() {
        mvc.delete("/api/ledger-entries/999999") {
            with(csrf())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }
}
