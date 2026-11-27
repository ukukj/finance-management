package com.investment.dashboard.dividend

import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.holding.Holding
import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.isValidDividend
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

object DividendEstimator {

    fun autoProfile(entries: List<LedgerEntry>, symbol: String, today: LocalDate): AutoProfile? {
        val payouts = entries
            .filter { it.symbol == symbol && it.isValidDividend(today) }
            .map { it.toPayout() }
        val lastPaid = payouts.maxOfOrNull { it.date }
        val latestHeld = payouts.groupBy { it.date }.entries
            .sortedByDescending { it.key }
            .asSequence()
            .map { (date, sameDay) -> HeldRound(date, sameDay, PositionReplay.quantityAt(entries, symbol, date)) }
            .firstOrNull { it.quantity.signum() > 0 }
        if (lastPaid == null || !lastPaid.isAfter(today.minusMonths(STALE_MONTHS)) || latestHeld == null) {
            return null
        }
        val windowStart = lastPaid.minusMonths(RECENT_MONTHS)
        val recent = payouts.filter { it.date.isAfter(windowStart) }
        return AutoProfile(
            symbol = symbol,
            currency = latestHeld.currency,
            perShare = latestHeld.amount.divide(latestHeld.quantity, RATIO_SCALE, RoundingMode.HALF_UP),
            payMonths = recent.map { it.date.monthValue }.toSortedSet(),
            netRatio = recent.netRatio(),
            perShareBasisDate = latestHeld.date,
        )
    }

    fun accountNetRatio(entries: List<LedgerEntry>, currency: String, today: LocalDate): BigDecimal? {
        val windowStart = today.minusMonths(RECENT_MONTHS)
        val payouts = entries
            .filter { it.currency == currency && it.isValidDividend(today) && it.settlementDate.isAfter(windowStart) }
            .map { it.toPayout() }
        return if (payouts.isEmpty()) null else payouts.netRatio()
    }

    fun forecast(
        holding: Holding,
        kind: AccountKind,
        profile: DividendProfile,
        from: YearMonth,
        to: YearMonth,
    ): List<ForecastItem> {
        if (holding.quantity.signum() == 0) {
            return emptyList()
        }
        val payMonths = profile.payMonthSet()
        val gross = profile.perShare.multiply(holding.quantity).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val net = gross.multiply(profile.netRatio).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        return generateSequence(from) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(to) }
            .filter { it.monthValue in payMonths }
            .map { ForecastItem(holding.accountId, kind, holding.symbol, it, gross, net, profile.currency) }
            .toList()
    }
}

private class Payout(val date: LocalDate, val amount: BigDecimal, val netAmount: BigDecimal, val currency: String)

private class HeldRound(val date: LocalDate, sameDay: List<Payout>, val quantity: BigDecimal) {
    val amount: BigDecimal = sameDay.sumOf { it.amount }
    val currency: String = sameDay.first().currency
}

private fun LedgerEntry.toPayout(): Payout =
    Payout(settlementDate, checkNotNull(amount), checkNotNull(netAmount), currency)

private fun List<Payout>.netRatio(): BigDecimal =
    sumOf { it.netAmount }.divide(sumOf { it.amount }, RATIO_SCALE, RoundingMode.HALF_UP)

private const val RECENT_MONTHS = 12L
private const val STALE_MONTHS = 13L
private const val RATIO_SCALE = 6
private const val MONEY_SCALE = 4
