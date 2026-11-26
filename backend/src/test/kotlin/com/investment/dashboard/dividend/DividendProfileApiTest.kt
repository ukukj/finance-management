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
    @DisplayName("토스 계좌의 자동 배당 정보를 직접 고치면 출처 MANUAL·갱신 시각 지금·세후 비율 1 이 된다")
    fun manual_profile_replaces_auto_profile_on_toss_account() {
        val tossId = createTossAccount()
        holdingRepository.save(usdHolding(tossId, "SCHD"))
        profileRepository.save(autoProfile(tossId, "SCHD"))

        putProfile(tossId, "SCHD", "0.40", listOf(3, 6, 9, 12))

        val profile = profileRepository.findByAccountIdAndSymbol(tossId, "SCHD")
        assertThat(profile?.perShare).isEqualByComparingTo("0.40")
        assertThat(profile?.source).isEqualTo(ProfileSource.MANUAL)
        assertThat(profile?.updatedAt).isAtSameInstantAs(OffsetDateTime.parse("2026-11-22T10:00+09:00"))
        assertThat(profile?.netRatio).isEqualByComparingTo("1")
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

    private fun autoProfile(accountId: Long, symbol: String): DividendProfile {
        val profile = DividendProfile(accountId, symbol, BigDecimal("0.36"), "USD", "3,6,9,12", BigDecimal("0.85"))
        profile.source = ProfileSource.AUTO
        profile.updatedAt = OffsetDateTime.parse("2026-10-01T09:00+09:00")
        return profile
    }
}
