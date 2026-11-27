package com.investment.dashboard.dividend

import com.investment.dashboard.TestcontainersConfiguration
import com.investment.dashboard.support.ManualApiTestSupport
import com.investment.dashboard.support.TestClockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.put
import java.math.BigDecimal
import java.time.OffsetDateTime

@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
@WithMockUser
class DividendProfileApiTest : ManualApiTestSupport() {

    @Test
    @DisplayName("배당 정보 종목 코드에 공백이 있으면 400 이다")
    fun profile_symbol_with_whitespace_is_400() {
        val isaId = createIsa()

        val result = mvc.put("/api/accounts/{id}/dividend-profiles/{symbol}", isaId, "458730 ") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"perShare":120,"payMonths":[1,4,7,10]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("symbol")
        assertThat(profileRepository.findByAccountId(isaId)).isEmpty()
    }

    @Test
    @DisplayName("지급 월에 13 이 있으면 400 이다")
    fun pay_month_13_is_400() {
        val isaId = createIsa()

        val result = putProfileExpectingBadRequest(isaId, """{"perShare":120,"payMonths":[1,13]}""")

        assertThat(fieldKeysOf(result)).containsExactly("payMonths")
        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")).isNull()
    }

    @Test
    @DisplayName("지급 월에 0 이 있으면 400 이다")
    fun pay_month_0_is_400() {
        val isaId = createIsa()

        val result = putProfileExpectingBadRequest(isaId, """{"perShare":120,"payMonths":[0,6]}""")

        assertThat(fieldKeysOf(result)).containsExactly("payMonths")
    }

    @Test
    @DisplayName("지급 월이 겹치면 400 이다")
    fun duplicate_pay_months_are_400() {
        val isaId = createIsa()

        val result = putProfileExpectingBadRequest(isaId, """{"perShare":120,"payMonths":[4,4]}""")

        assertThat(fieldKeysOf(result)).containsExactly("payMonths")
    }

    @Test
    @DisplayName("지급 월이 하나도 없으면 400 이다")
    fun empty_pay_months_are_400() {
        val isaId = createIsa()

        val result = putProfileExpectingBadRequest(isaId, """{"perShare":120,"payMonths":[]}""")

        assertThat(fieldKeysOf(result)).containsExactly("payMonths")
    }

    @Test
    @DisplayName("주당 분배금이 0 이면 400 이다")
    fun zero_per_share_is_400() {
        val isaId = createIsa()

        val result = putProfileExpectingBadRequest(isaId, """{"perShare":0,"payMonths":[1,4,7,10]}""")

        assertThat(fieldKeysOf(result)).containsExactly("perShare")
        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")).isNull()
    }

    @Test
    @DisplayName("주당 분배금의 소수가 6자리를 넘으면 400 이다")
    fun per_share_with_more_than_six_decimals_is_400() {
        val isaId = createIsa()

        val result = putProfileExpectingBadRequest(isaId, """{"perShare":120.1234567,"payMonths":[1,4,7,10]}""")

        assertThat(fieldKeysOf(result)).containsExactly("perShare")
        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")).isNull()
    }

    @Test
    @DisplayName("지급 월은 정렬해 저장한다")
    fun pay_months_are_stored_sorted() {
        val isaId = createIsa()

        putProfile(isaId, "458730", "120", listOf(10, 1, 7, 4))

        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")?.payMonths).isEqualTo("1,4,7,10")
    }

    @Test
    @DisplayName("토스 계좌의 자동 배당 정보를 직접 고치면 출처 MANUAL·갱신 시각 지금이 되고 세후 비율은 기존 값을 유지한다")
    fun manual_profile_replaces_auto_profile_on_toss_account() {
        val tossId = createTossAccount()
        holdingRepository.save(usdHolding(tossId, "SCHD"))
        profileRepository.save(autoProfile(tossId, "SCHD"))

        putProfile(tossId, "SCHD", "0.40", listOf(3, 6, 9, 12))

        val profile = profileRepository.findByAccountIdAndSymbol(tossId, "SCHD")
        assertThat(profile?.perShare).isEqualByComparingTo("0.40")
        assertThat(profile?.source).isEqualTo(ProfileSource.MANUAL)
        assertThat(profile?.updatedAt).isAtSameInstantAs(OffsetDateTime.parse("2026-11-22T10:00+09:00"))
        assertThat(profile?.netRatio).isEqualByComparingTo("0.85")
    }

    @Test
    @DisplayName("토스 계좌 신규 종목의 세후 비율은 그 계좌 같은 통화 최근 12개월 배당의 세후 합 ÷ 세전 합이다")
    fun new_toss_profile_net_ratio_is_account_average_of_same_currency() {
        val tossId = createTossAccount()
        holdingRepository.save(usdHolding(tossId, "VOO"))
        ledgerRepository.save(otherSymbolDividend(tossId, "USD", "100.0000", "85.0000", "2026-03-15"))
        ledgerRepository.save(otherSymbolDividend(tossId, "USD", "100.0000", "84.0000", "2026-06-15"))

        putProfile(tossId, "VOO", "1.50", listOf(3, 6, 9, 12))

        assertThat(profileRepository.findByAccountIdAndSymbol(tossId, "VOO")?.netRatio)
            .isEqualByComparingTo("0.845000")
    }

    @Test
    @DisplayName("토스 계좌 평균은 결제일이 정확히 12개월 전인 배당을 넣지 않는다")
    fun toss_account_average_leaves_out_dividend_paid_exactly_twelve_months_ago() {
        val tossId = createTossAccount()
        holdingRepository.save(usdHolding(tossId, "VOO"))
        ledgerRepository.save(otherSymbolDividend(tossId, "USD", "100.0000", "50.0000", "2025-11-22"))
        ledgerRepository.save(otherSymbolDividend(tossId, "USD", "100.0000", "85.0000", "2025-11-23"))

        putProfile(tossId, "VOO", "1.50", listOf(3, 6, 9, 12))

        assertThat(profileRepository.findByAccountIdAndSymbol(tossId, "VOO")?.netRatio)
            .isEqualByComparingTo("0.850000")
    }

    @Test
    @DisplayName("토스 계좌 신규 종목에 다른 통화 배당 이력만 있으면 세후 비율은 1 이다")
    fun new_toss_profile_net_ratio_is_one_with_only_other_currency_history() {
        val tossId = createTossAccount()
        holdingRepository.save(usdHolding(tossId, "VOO"))
        ledgerRepository.save(otherSymbolDividend(tossId, "KRW", "100000", "84600", "2026-03-10"))

        putProfile(tossId, "VOO", "1.50", listOf(3, 6, 9, 12))

        assertThat(profileRepository.findByAccountIdAndSymbol(tossId, "VOO")?.netRatio).isEqualByComparingTo("1")
    }

    @Test
    @DisplayName("토스 계좌 신규 종목에 배당 이력이 없으면 세후 비율은 1 이다")
    fun new_toss_profile_net_ratio_is_one_without_history() {
        val tossId = createTossAccount()
        holdingRepository.save(usdHolding(tossId, "VOO"))

        putProfile(tossId, "VOO", "1.50", listOf(3, 6, 9, 12))

        assertThat(profileRepository.findByAccountIdAndSymbol(tossId, "VOO")?.netRatio).isEqualByComparingTo("1")
    }

    @Test
    @DisplayName("미래에셋 계좌 신규 종목의 세후 비율은 계좌 배당 이력과 관계없이 1 이다")
    fun new_mirae_profile_net_ratio_is_one_regardless_of_history() {
        val isaId = createIsa()
        ledgerRepository.save(otherSymbolDividend(isaId, "KRW", "100000", "84600", "2026-03-10"))

        putProfile(isaId, "458730", "120", listOf(1, 4, 7, 10))

        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")?.netRatio).isEqualByComparingTo("1")
    }

    @Test
    @DisplayName("배당 정보 통화는 보유 종목 통화를 따른다")
    fun profile_currency_follows_holding_currency() {
        val tossId = createTossAccount()
        holdingRepository.save(usdHolding(tossId, "SCHD"))

        putProfile(tossId, "SCHD", "0.40", listOf(3, 6, 9, 12))

        assertThat(profileRepository.findByAccountIdAndSymbol(tossId, "SCHD")?.currency).isEqualTo("USD")
    }

    @Test
    @DisplayName("보유 종목이 없으면 배당 정보 통화는 KRW 다")
    fun profile_currency_without_holding_is_krw() {
        val isaId = createIsa()

        putProfile(isaId, "458730", "120", listOf(1, 4, 7, 10))

        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")?.currency).isEqualTo("KRW")
    }

    private fun putProfileExpectingBadRequest(accountId: Long, body: String): MvcResult =
        mvc.put("/api/accounts/$accountId/dividend-profiles/458730") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

    private fun otherSymbolDividend(accountId: Long, currency: String, amount: String, net: String, date: String) =
        dividend("OTHER", amount, net, currency, date).apply { this.accountId = accountId }

    private fun autoProfile(accountId: Long, symbol: String): DividendProfile {
        val profile = DividendProfile(accountId, symbol, BigDecimal("0.36"), "USD", "3,6,9,12", BigDecimal("0.85"))
        profile.source = ProfileSource.AUTO
        profile.updatedAt = OffsetDateTime.parse("2026-10-01T09:00+09:00")
        return profile
    }
}
