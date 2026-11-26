package com.investment.dashboard.taxcard

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object TaxCardCalculator {

    fun pension(paidThisYear: BigDecimal, creditLimit: BigDecimal, today: LocalDate): PensionFigures {
        val paid = won(paidThisYear)
        val limit = won(creditLimit)
        val remaining = maxOf(0L, limit - paid)
        val monthsLeft = Month.DECEMBER.value - today.monthValue + 1
        return PensionFigures(
            paidThisYear = paid,
            creditLimit = limit,
            remaining = remaining,
            daysLeft = ChronoUnit.DAYS.between(today, today.with(TemporalAdjusters.lastDayOfYear())),
            monthlyNeeded = Math.ceilDiv(remaining, monthsLeft.toLong()),
        )
    }

    fun isa(terms: IsaTerms, rules: IsaRules, paidThisYear: BigDecimal, today: LocalDate): IsaFigures {
        val years = today.year - terms.openDate.year + 1
        val limit = rules.annualLimit.multiply(BigDecimal(years)).min(rules.totalLimit)
        val available = maxOf(0L, won(limit) - won(terms.priorTotal))
        val paid = won(paidThisYear)
        return IsaFigures(
            annualAvailable = available,
            paidThisYear = paid,
            remaining = maxOf(0L, available - paid),
            maturityDate = terms.maturityDate,
            transferDeadline = terms.maturityDate.plusDays(rules.transferDays.toLong()),
        )
    }

    private fun won(amount: BigDecimal): Long = amount.setScale(0, RoundingMode.HALF_UP).longValueExact()
}
