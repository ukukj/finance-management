package com.investment.dashboard.holding

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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.put
import java.time.OffsetDateTime

@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
@WithMockUser
class ManualHoldingApiTest : ManualApiTestSupport() {

    @Test
    @DisplayName("토스 계좌에 수동 보유 수량을 넣으면 409 MANUAL_NOT_ALLOWED 다")
    fun manual_holding_on_toss_account_is_409() {
        val tossId = createTossAccount()

        mvc.put("/api/accounts/$tossId/holdings/458730") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"테스트 ETF","quantity":100}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("MANUAL_NOT_ALLOWED") }
        }

        assertThat(holdingRepository.findByAccountIdAndSymbol(tossId, "458730")).isNull()
    }

    @Test
    @DisplayName("미래에셋 계좌에 보유 수량을 넣으면 국내 원화 종목으로 저장된다")
    fun manual_holding_is_saved_as_krw_holding_in_kr_market() {
        val isaId = createIsa()

        putHolding(isaId, "458730", "100")

        val holding = holdingRepository.findByAccountIdAndSymbol(isaId, "458730")
        assertThat(holding?.name).isEqualTo("테스트 ETF")
        assertThat(holding?.quantity).isEqualByComparingTo("100")
        assertThat(holding?.marketCountry).isEqualTo("KR")
        assertThat(holding?.currency).isEqualTo("KRW")
        assertThat(holding?.updatedAt).isAtSameInstantAs(OffsetDateTime.parse("2026-11-22T10:00+09:00"))
    }

    @Test
    @DisplayName("같은 종목 보유 수량을 다시 넣으면 이름과 수량을 바꾼다")
    fun manual_holding_put_again_replaces_name_and_quantity() {
        val isaId = createIsa()
        putHolding(isaId, "458730", "100")

        mvc.put("/api/accounts/$isaId/holdings/458730") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"새 이름 ETF","quantity":150}"""
        }.andExpect { status { isOk() } }

        val holdings = holdingRepository.findByAccountId(isaId)
        assertThat(holdings).hasSize(1)
        assertThat(holdings.first().name).isEqualTo("새 이름 ETF")
        assertThat(holdings.first().quantity).isEqualByComparingTo("150")
    }

    @Test
    @DisplayName("보유 수량의 소수가 6자리를 넘으면 400 이다")
    fun holding_quantity_with_more_than_six_decimals_is_400() {
        val isaId = createIsa()

        val result = mvc.put("/api/accounts/$isaId/holdings/458730") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"테스트 ETF","quantity":1.1234567}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("quantity")
        assertThat(holdingRepository.findByAccountId(isaId)).isEmpty()
    }

    @Test
    @DisplayName("종목 코드가 20자를 넘으면 400 이다")
    fun symbol_longer_than_20_is_400() {
        val isaId = createIsa()

        val result = mvc.put("/api/accounts/$isaId/holdings/ABCDEFGHIJKLMNOPQRSTU") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"테스트 ETF","quantity":100}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("symbol")
        assertThat(holdingRepository.findByAccountId(isaId)).isEmpty()
    }

    @Test
    @DisplayName("종목 코드에 공백이 있으면 400 이다")
    fun symbol_with_whitespace_is_400() {
        val isaId = createIsa()

        val result = mvc.put("/api/accounts/{id}/holdings/{symbol}", isaId, "458730 ") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"테스트 ETF","quantity":100}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("symbol")
        assertThat(holdingRepository.findByAccountId(isaId)).isEmpty()
    }

    @Test
    @DisplayName("보유 종목 이름이 공백이면 400 이다")
    fun blank_holding_name_is_400() {
        val isaId = createIsa()

        val result = mvc.put("/api/accounts/$isaId/holdings/458730") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"   ","quantity":100}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("name")
        assertThat(holdingRepository.findByAccountId(isaId)).isEmpty()
    }

    @Test
    @DisplayName("보유 종목 이름이 100자를 넘으면 400 이다")
    fun holding_name_longer_than_100_is_400() {
        val isaId = createIsa()

        val result = mvc.put("/api/accounts/$isaId/holdings/458730") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"${"가".repeat(101)}","quantity":100}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("name")
        assertThat(holdingRepository.findByAccountId(isaId)).isEmpty()
    }

    @Test
    @DisplayName("없는 계좌에 보유 수량을 넣으면 404 NOT_FOUND 다")
    fun manual_holding_on_unknown_account_is_404() {
        mvc.put("/api/accounts/999999/holdings/458730") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"테스트 ETF","quantity":100}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    @DisplayName("보유 종목을 지우면 204 이고 배당 정보는 남는다")
    fun delete_holding_keeps_dividend_profile() {
        val isaId = createIsa()
        putHolding(isaId, "458730", "100")
        putProfile(isaId, "458730", "120", listOf(1, 4, 7, 10))

        mvc.delete("/api/accounts/$isaId/holdings/458730") {
            with(csrf())
        }.andExpect { status { isNoContent() } }

        assertThat(holdingRepository.findByAccountIdAndSymbol(isaId, "458730")).isNull()
        assertThat(profileRepository.findByAccountIdAndSymbol(isaId, "458730")?.perShare).isEqualByComparingTo("120")
    }

    @Test
    @DisplayName("없는 보유 종목을 지우면 404 NOT_FOUND 다")
    fun delete_unknown_holding_is_404() {
        val isaId = createIsa()

        mvc.delete("/api/accounts/$isaId/holdings/458730") {
            with(csrf())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    @DisplayName("토스 계좌의 보유 종목을 지우면 409 MANUAL_NOT_ALLOWED 다")
    fun delete_holding_on_toss_account_is_409() {
        val tossId = createTossAccount()
        holdingRepository.save(usdHolding(tossId, "SCHD"))

        mvc.delete("/api/accounts/$tossId/holdings/SCHD") {
            with(csrf())
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("MANUAL_NOT_ALLOWED") }
        }

        assertThat(holdingRepository.findByAccountIdAndSymbol(tossId, "SCHD")).isNotNull()
    }
}
