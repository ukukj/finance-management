package com.investment.dashboard.manual

import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.dividend.DividendProfile
import com.investment.dashboard.holding.Holding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class PendingDividendsTest {

    @Test
    @DisplayName("확인 대기 목록은 월 순, 같은 달이면 종목 코드 순이다")
    fun pending_dividends_are_ordered_by_month_then_symbol() {
        val holdings = listOf(holding("999990", "10"), holding("458730", "100"))
        val profiles = listOf(profile("999990", "500", "1,4"), profile("458730", "120", "4,10"))

        val pending = PendingDividends.of(
            AccountKind.ISA,
            holdings,
            profiles,
            emptyList(),
            LocalDate.parse("2026-11-22"),
        )

        assertThat(pending.map { it.symbol to it.month }).containsExactly(
            "999990" to YearMonth.parse("2026-01"),
            "458730" to YearMonth.parse("2026-04"),
            "999990" to YearMonth.parse("2026-04"),
            "458730" to YearMonth.parse("2026-10"),
        )
    }

    private fun holding(symbol: String, quantity: String) =
        Holding(1L, symbol, "테스트 ETF", "KR", "KRW", BigDecimal(quantity))

    private fun profile(symbol: String, perShare: String, payMonths: String) =
        DividendProfile(1L, symbol, BigDecimal(perShare), "KRW", payMonths, BigDecimal.ONE)
}
