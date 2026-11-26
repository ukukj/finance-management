package com.investment.dashboard.taxcard

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class TaxCardCalculatorTest {

    @Test
    @DisplayName("연금저축 600만원 중 470만원 넣은 11월 22일엔 130만원, 39일, 월 65만원이 남는다")
    fun pension_remaining() {
        val figures =
            TaxCardCalculator.pension(BigDecimal("4700000"), BigDecimal("6000000"), LocalDate.parse("2026-11-22"))

        assertThat(figures.remaining).isEqualTo(1300000L)
        assertThat(figures.daysLeft).isEqualTo(39L)
        assertThat(figures.monthlyNeeded).isEqualTo(650000L)
    }

    @Test
    @DisplayName("한도보다 많이 넣어도 남은 금액은 0 이다")
    fun pension_paid_over_limit_leaves_zero() {
        val figures =
            TaxCardCalculator.pension(BigDecimal("6500000"), BigDecimal("6000000"), LocalDate.parse("2026-11-22"))

        assertThat(figures.remaining).isEqualTo(0L)
        assertThat(figures.monthlyNeeded).isEqualTo(0L)
    }

    @Test
    @DisplayName("남은 금액이 남은 달 수로 나누어떨어지지 않으면 월 필요액은 올림한다")
    fun pension_monthly_needed_rounds_up() {
        val figures =
            TaxCardCalculator.pension(BigDecimal("4700001"), BigDecimal("6000000"), LocalDate.parse("2026-11-22"))

        assertThat(figures.remaining).isEqualTo(1299999L)
        assertThat(figures.monthlyNeeded).isEqualTo(650000L)
    }

    @Test
    @DisplayName("12월 31일엔 남은 날이 0 이고 남은 금액 전부가 월 필요액이다")
    fun pension_on_december_31_needs_all_remaining_this_month() {
        val figures =
            TaxCardCalculator.pension(BigDecimal("4700000"), BigDecimal("6000000"), LocalDate.parse("2026-12-31"))

        assertThat(figures.daysLeft).isEqualTo(0L)
        assertThat(figures.monthlyNeeded).isEqualTo(1300000L)
    }

    @Test
    @DisplayName("올해 납입액의 원 미만은 반올림하고 남은 금액은 반올림한 납입액으로 계산한다")
    fun pension_paid_this_year_is_rounded_half_up_before_remaining() {
        val figures =
            TaxCardCalculator.pension(BigDecimal("4700000.5"), BigDecimal("6000000"), LocalDate.parse("2026-11-22"))

        assertThat(figures.paidThisYear).isEqualTo(4700001L)
        assertThat(figures.creditLimit).isEqualTo(6000000L)
        assertThat(figures.remaining).isEqualTo(1299999L)
    }

    @Test
    @DisplayName("ISA 3년차 납입 가능액은 연 한도 × 3 에서 이전 누적 납입을 뺀 것이다")
    fun isa_third_year_available() {
        val terms = IsaTerms(LocalDate.parse("2024-03-01"), LocalDate.parse("2027-03-01"), BigDecimal("15000000"))

        val figures = TaxCardCalculator.isa(terms, RULES_2026, BigDecimal("5000000"), LocalDate.parse("2026-11-22"))

        assertThat(figures.annualAvailable).isEqualTo(45000000L)
        assertThat(figures.paidThisYear).isEqualTo(5000000L)
        assertThat(figures.remaining).isEqualTo(40000000L)
    }

    @Test
    @DisplayName("ISA 납입 가능액은 총 한도 1억을 넘지 않는다")
    fun isa_available_is_capped_by_total_limit() {
        val terms = IsaTerms(LocalDate.parse("2021-01-10"), LocalDate.parse("2027-01-10"), BigDecimal("95000000"))

        val figures = TaxCardCalculator.isa(terms, RULES_2026, BigDecimal("0"), LocalDate.parse("2026-11-22"))

        assertThat(figures.annualAvailable).isEqualTo(5000000L)
    }

    @Test
    @DisplayName("ISA 이전 누적 납입이 납입 한도보다 많으면 납입 가능액은 0 이다")
    fun isa_prior_total_over_limit_leaves_zero_available() {
        val terms = IsaTerms(LocalDate.parse("2026-01-10"), LocalDate.parse("2029-01-10"), BigDecimal("25000000"))

        val figures = TaxCardCalculator.isa(terms, RULES_2026, BigDecimal("0"), LocalDate.parse("2026-11-22"))

        assertThat(figures.annualAvailable).isEqualTo(0L)
        assertThat(figures.remaining).isEqualTo(0L)
    }

    @Test
    @DisplayName("ISA 올해 납입이 납입 가능액보다 많으면 남은 금액은 0 이다")
    fun isa_paid_over_available_leaves_zero_remaining() {
        val terms = IsaTerms(LocalDate.parse("2024-03-01"), LocalDate.parse("2027-03-01"), BigDecimal("15000000"))

        val figures = TaxCardCalculator.isa(terms, RULES_2026, BigDecimal("50000000"), LocalDate.parse("2026-11-22"))

        assertThat(figures.remaining).isEqualTo(0L)
    }

    @Test
    @DisplayName("연금 이전 마감일은 만기일 + 60일이다")
    fun isa_transfer_deadline_is_maturity_plus_60_days() {
        val terms = IsaTerms(LocalDate.parse("2024-03-01"), LocalDate.parse("2027-03-01"), BigDecimal("15000000"))

        val figures = TaxCardCalculator.isa(terms, RULES_2026, BigDecimal("5000000"), LocalDate.parse("2026-11-22"))

        assertThat(figures.maturityDate).isEqualTo(LocalDate.parse("2027-03-01"))
        assertThat(figures.transferDeadline).isEqualTo(LocalDate.parse("2027-04-30"))
    }

    private companion object {
        val RULES_2026 = IsaRules(BigDecimal("20000000"), BigDecimal("100000000"), 60)
    }
}
