package com.investment.dashboard.dividend

import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.holding.Holding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class DividendEstimatorTest {

    @Test
    @DisplayName("최근 배당액을 그 결제일 보유 수량으로 나눠 주당 배당과 세후 비율을 낸다")
    fun per_share_from_latest_dividend() {
        val entries = listOf(
            buy("SCHD", "100", "2025-12-01"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2026-03-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))!!

        assertThat(profile.perShare).isEqualByComparingTo("0.360000")
        assertThat(profile.netRatio).isEqualByComparingTo("0.850000")
    }

    @Test
    @DisplayName("보유 수량이 있는 회차가 여럿이면 가장 최근 회차로 주당 배당을 낸다")
    fun per_share_uses_most_recent_held_round() {
        val entries = listOf(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "30.0000", "25.5000", "USD", "2026-03-15"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2026-06-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.perShare).isEqualByComparingTo("0.360000")
    }

    @Test
    @DisplayName("같은 결제일 배당 행이 여럿이면 금액을 합쳐 주당 배당을 낸다")
    fun same_day_dividends_are_summed_for_per_share() {
        val entries = listOf(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "30.0000", "25.5000", "USD", "2026-06-15"),
            dividend("SCHD", "6.0000", "5.1000", "USD", "2026-06-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.perShare).isEqualByComparingTo("0.360000")
    }

    @Test
    @DisplayName("주당 배당은 소수 일곱째 자리에서 반올림해 여섯째 자리까지 낸다")
    fun per_share_is_rounded_half_up_to_six_places() {
        val entries = listOf(
            buy("SCHD", "3", "2026-01-02"),
            dividend("SCHD", "2.0000", "2.0000", "USD", "2026-06-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.perShare).isEqualByComparingTo("0.666667")
    }

    @Test
    @DisplayName("주당 배당은 일곱째 자리 아래가 반 미만이면 버린다")
    fun per_share_drops_less_than_half() {
        val entries = listOf(
            buy("SCHD", "3", "2026-01-02"),
            dividend("SCHD", "1.0000", "1.0000", "USD", "2026-06-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.perShare).isEqualByComparingTo("0.333333")
    }

    @Test
    @DisplayName("주당 배당은 일곱째 자리 아래가 정확히 반이면 올린다")
    fun per_share_rounds_exact_half_up() {
        val entries = listOf(
            buy("SCHD", "8", "2026-01-02"),
            dividend("SCHD", "2.0001", "2.0001", "USD", "2026-06-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.perShare).isEqualByComparingTo("0.250013")
    }

    @Test
    @DisplayName("세후 비율은 최근 12개월 배당의 세후 합 ÷ 세전 합으로 낸다")
    fun net_ratio_is_net_sum_over_gross_sum_of_last_twelve_months() {
        val entries = listOf(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "100.0000", "100.0000", "USD", "2026-08-15"),
            dividend("SCHD", "100.0000", "84.6000", "USD", "2026-09-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.netRatio).isEqualByComparingTo("0.923000")
    }

    @Test
    @DisplayName("세후 비율은 소수 일곱째 자리에서 반올림해 여섯째 자리까지 낸다")
    fun net_ratio_is_rounded_half_up_to_six_places() {
        val entries = listOf(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "3.0000", "2.0000", "USD", "2026-06-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.netRatio).isEqualByComparingTo("0.666667")
    }

    @Test
    @DisplayName("세후 비율은 일곱째 자리 아래가 반 미만이면 버린다")
    fun net_ratio_drops_less_than_half() {
        val entries = listOf(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "3.0000", "2.5000", "USD", "2026-06-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.netRatio).isEqualByComparingTo("0.833333")
    }

    @Test
    @DisplayName("세후 비율은 일곱째 자리 아래가 정확히 반이면 올린다")
    fun net_ratio_rounds_exact_half_up() {
        val entries = listOf(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "8.0000", "6.8001", "USD", "2026-06-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.netRatio).isEqualByComparingTo("0.850013")
    }

    @Test
    @DisplayName("12개월보다 오래된 배당은 세후 비율에 넣지 않는다")
    fun dividends_older_than_twelve_months_are_left_out_of_net_ratio() {
        val entries = listOf(
            buy("SCHD", "100", "2025-06-02"),
            dividend("SCHD", "100.0000", "50.0000", "USD", "2025-10-15"),
            dividend("SCHD", "100.0000", "85.0000", "USD", "2026-09-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.netRatio).isEqualByComparingTo("0.850000")
    }

    @Test
    @DisplayName("최근 12개월 배당 월을 지급 월로 쓴다")
    fun pay_months_from_last_twelve_months() {
        val entries = listOf(
            buy("SCHD", "100", "2025-06-02"),
            dividendOn("SCHD", "2025-09-15"),
            dividendOn("SCHD", "2025-12-15"),
            dividendOn("SCHD", "2026-03-15"),
            dividendOn("SCHD", "2026-06-15"),
            dividendOn("SCHD", "2026-09-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.payMonths).containsExactly(3, 6, 9, 12)
    }

    @Test
    @DisplayName("결제일이 정확히 12개월 전인 배당은 지급 월에 넣지 않는다")
    fun dividend_paid_exactly_twelve_months_ago_is_not_a_pay_month() {
        val entries = listOf(
            buy("SCHD", "100", "2025-06-02"),
            dividendOn("SCHD", "2025-11-22"),
            dividendOn("SCHD", "2026-05-22"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.payMonths).containsExactly(5)
    }

    @Test
    @DisplayName("오늘 결제된 배당은 넣고 결제일이 내일인 배당은 계산에서 뺀다")
    fun dividend_paid_today_is_counted_and_tomorrow_is_not() {
        val entries = listOf(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2026-11-22"),
            dividend("SCHD", "50.0000", "50.0000", "USD", "2026-11-23"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.perShare).isEqualByComparingTo("0.360000")
        assertThat(profile?.netRatio).isEqualByComparingTo("0.850000")
    }

    @Test
    @DisplayName("주당 배당 기준일은 앞선 회차나 뒤의 수량 0 회차가 아니라 주당 배당을 낸 회차의 결제일이다")
    fun per_share_basis_date_is_date_of_round_used_for_per_share() {
        val entries = listOf(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "30.0000", "25.5000", "USD", "2026-03-15"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2026-06-15"),
            sell("SCHD", "100", "2026-09-01"),
            dividend("SCHD", "40.0000", "34.0000", "USD", "2026-10-15"),
            buy("SCHD", "100", "2026-10-20"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.perShareBasisDate).isEqualTo(LocalDate.parse("2026-06-15"))
    }

    @Test
    @DisplayName("배당일 수량이 0 인 최근 회차는 건너뛰고 그 이전 회차로 계산한다")
    fun skips_latest_round_held_with_zero_quantity() {
        val entries = listOf(
            buy("SCHD", "50", "2026-01-02"),
            dividend("SCHD", "18.0000", "15.3000", "USD", "2026-06-15"),
            sell("SCHD", "50", "2026-08-01"),
            dividend("SCHD", "20.0000", "17.0000", "USD", "2026-09-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.perShare).isEqualByComparingTo("0.360000")
    }

    @Test
    @DisplayName("모든 배당 회차의 수량이 0 이면 자동 추정이 없다")
    fun no_auto_profile_when_every_round_has_zero_quantity() {
        val entries = listOf(
            dividend("SCHD", "18.0000", "15.3000", "USD", "2026-06-15"),
            dividend("SCHD", "20.0000", "17.0000", "USD", "2026-09-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile).isNull()
    }

    @Test
    @DisplayName("마지막 배당이 12개월보다 오래되면 자동 추정이 없다")
    fun no_auto_profile_when_last_dividend_is_older_than_twelve_months() {
        val entries = listOf(
            buy("SCHD", "100", "2025-06-02"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2025-10-01"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile).isNull()
    }

    @Test
    @DisplayName("금액이 0 인 배당 행은 주당 배당과 지급 월 계산에서 뺀다")
    fun zero_amount_dividend_is_left_out() {
        val entries = listOf(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2026-03-15"),
            dividend("SCHD", "0", "0", "USD", "2026-06-15"),
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.perShare).isEqualByComparingTo("0.360000")
        assertThat(profile?.payMonths).containsExactly(3)
    }

    @Test
    @DisplayName("세전 금액이 없는 배당 행은 세후 금액이 있어도 주당 배당과 지급 월 계산에서 뺀다")
    fun dividend_without_amount_is_left_out() {
        val entries = listOf(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2026-03-15"),
            dividend("SCHD", "40.0000", "34.0000", "USD", "2026-06-15").apply { amount = null },
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.perShare).isEqualByComparingTo("0.360000")
        assertThat(profile?.payMonths).containsExactly(3)
    }

    @Test
    @DisplayName("세후 금액이 없는 배당 행은 주당 배당·지급 월·세후 비율 계산에서 뺀다")
    fun dividend_without_net_amount_is_left_out() {
        val entries = listOf(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2026-03-15"),
            dividend("SCHD", "40.0000", "40.0000", "USD", "2026-06-15").apply { netAmount = null },
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.perShare).isEqualByComparingTo("0.360000")
        assertThat(profile?.payMonths).containsExactly(3)
        assertThat(profile?.netRatio).isEqualByComparingTo("0.850000")
    }

    @Test
    @DisplayName("취소된 배당 행은 계산에서 뺀다")
    fun cancelled_dividend_is_left_out() {
        val entries = listOf(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2026-03-15"),
            dividend("SCHD", "40.0000", "34.0000", "USD", "2026-06-15").apply { isCancellation = true },
        )

        val profile = DividendEstimator.autoProfile(entries, "SCHD", LocalDate.parse("2026-11-22"))

        assertThat(profile?.perShare).isEqualByComparingTo("0.360000")
        assertThat(profile?.payMonths).containsExactly(3)
    }

    @Test
    @DisplayName("지급 월에만 주당 배당 × 현재 수량으로 예상을 만든다")
    fun forecast_only_in_pay_months() {
        val holding = Holding(1L, "SCHD", "테스트 배당 ETF", "US", "USD", BigDecimal("120"))
        val profile = DividendProfile(1L, "SCHD", BigDecimal("0.36"), "USD", "3,6,9,12", BigDecimal("0.85"))

        val items = DividendEstimator.forecast(
            holding,
            AccountKind.BROKERAGE,
            profile,
            YearMonth.of(2026, 12),
            YearMonth.of(2027, 3),
        )

        assertThat(items).containsExactlyInAnyOrder(
            ForecastItem(
                1L,
                AccountKind.BROKERAGE,
                "SCHD",
                YearMonth.of(2026, 12),
                BigDecimal("43.2000"),
                BigDecimal("36.7200"),
                "USD",
            ),
            ForecastItem(
                1L,
                AccountKind.BROKERAGE,
                "SCHD",
                YearMonth.of(2027, 3),
                BigDecimal("43.2000"),
                BigDecimal("36.7200"),
                "USD",
            ),
        )
    }

    @Test
    @DisplayName("예상 세전·세후 금액은 소수 다섯째 자리에서 반올림해 넷째 자리까지 낸다")
    fun forecast_amounts_are_rounded_half_up_to_four_places() {
        val holding = Holding(1L, "SCHD", "테스트 배당 ETF", "US", "USD", BigDecimal("7"))
        val profile = DividendProfile(1L, "SCHD", BigDecimal("0.666667"), "USD", "12", BigDecimal("0.85"))

        val items = DividendEstimator.forecast(
            holding,
            AccountKind.BROKERAGE,
            profile,
            YearMonth.of(2026, 12),
            YearMonth.of(2026, 12),
        )

        assertThat(items).containsExactly(
            ForecastItem(
                1L,
                AccountKind.BROKERAGE,
                "SCHD",
                YearMonth.of(2026, 12),
                BigDecimal("4.6667"),
                BigDecimal("3.9667"),
                "USD",
            ),
        )
    }

    @Test
    @DisplayName("예상 세전·세후 금액은 다섯째 자리 아래가 반 미만이면 버린다")
    fun forecast_amounts_drop_less_than_half() {
        val holding = Holding(1L, "SCHD", "테스트 배당 ETF", "US", "USD", BigDecimal("7"))
        val profile = DividendProfile(1L, "SCHD", BigDecimal("0.333333"), "USD", "12", BigDecimal("0.85"))

        val items = DividendEstimator.forecast(
            holding,
            AccountKind.BROKERAGE,
            profile,
            YearMonth.of(2026, 12),
            YearMonth.of(2026, 12),
        )

        assertThat(items).containsExactly(
            ForecastItem(
                1L,
                AccountKind.BROKERAGE,
                "SCHD",
                YearMonth.of(2026, 12),
                BigDecimal("2.3333"),
                BigDecimal("1.9833"),
                "USD",
            ),
        )
    }

    @Test
    @DisplayName("예상 세전·세후 금액은 다섯째 자리 아래가 정확히 반이면 올린다")
    fun forecast_amounts_round_exact_half_up() {
        val holding = Holding(1L, "SCHD", "테스트 배당 ETF", "US", "USD", BigDecimal("7"))
        val profile = DividendProfile(1L, "SCHD", BigDecimal("0.100350"), "USD", "12", BigDecimal("0.9"))

        val items = DividendEstimator.forecast(
            holding,
            AccountKind.BROKERAGE,
            profile,
            YearMonth.of(2026, 12),
            YearMonth.of(2026, 12),
        )

        assertThat(items).containsExactly(
            ForecastItem(
                1L,
                AccountKind.BROKERAGE,
                "SCHD",
                YearMonth.of(2026, 12),
                BigDecimal("0.7025"),
                BigDecimal("0.6323"),
                "USD",
            ),
        )
    }

    @Test
    @DisplayName("현재 수량이 0 이면 예상이 없다")
    fun no_forecast_when_quantity_is_zero() {
        val holding = Holding(1L, "SCHD", "테스트 배당 ETF", "US", "USD", BigDecimal("0"))
        val profile = DividendProfile(1L, "SCHD", BigDecimal("0.36"), "USD", "3,6,9,12", BigDecimal("0.85"))

        val items = DividendEstimator.forecast(
            holding,
            AccountKind.BROKERAGE,
            profile,
            YearMonth.of(2026, 12),
            YearMonth.of(2027, 3),
        )

        assertThat(items).isEmpty()
    }
}
