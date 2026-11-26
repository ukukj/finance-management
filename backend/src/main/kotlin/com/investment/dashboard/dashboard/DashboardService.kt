package com.investment.dashboard.dashboard

import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.dividend.DividendEstimator
import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.ledger.LedgerEntryType
import com.investment.dashboard.ledger.isValidDividend
import com.investment.dashboard.sync.SyncStatusReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

@Service
class DashboardService(
    private val accountRepository: AccountRepository,
    private val ledgerEntryRepository: LedgerEntryRepository,
    private val heldPositionReader: HeldPositionReader,
    private val krwConverter: KrwConverter,
    private val syncStatusReader: SyncStatusReader,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun build(): DashboardResponse {
        val today = LocalDate.now(clock)
        val thisMonth = YearMonth.from(today)
        val kinds = accountRepository.findAll().associate { checkNotNull(it.id) to it.kind }
        val dividends = ledgerEntryRepository.findByType(LedgerEntryType.DIVIDEND).filter { it.isValidDividend(today) }
        val receivedMonths = dividends.map { ReceivedMonth(it.accountId, it.symbol, YearMonth.from(it.settlementDate)) }
            .toSet()
        val receivedThisYear = dividends.filter { it.settlementDate.year == today.year }
            .map { Received(kinds.getValue(it.accountId), it.settlementDate, krwConverter.actual(it)) }
        val positions = heldPositionReader.read()
        val expected = positions
            .flatMap { expectedOf(it, kinds.getValue(it.holding.accountId), thisMonth) }
            .filterNot { ReceivedMonth(it.accountId, it.symbol, it.month) in receivedMonths }
        return DashboardResponse(
            headline = headline(receivedThisYear, expected, thisMonth),
            months = months(today.year, receivedThisYear, expected),
            holdings = holdingRows(positions, kinds, dividends, expected),
            sync = syncStatusReader.latest(),
        )
    }

    private fun expectedOf(position: HeldPosition, kind: AccountKind, thisMonth: YearMonth): List<Expected> {
        val profile = position.profile ?: return emptyList()
        val to = thisMonth.plusMonths(FORECAST_MONTHS)
        return DividendEstimator.forecast(position.holding, kind, profile, thisMonth, to)
            .map { Expected(it.accountId, it.accountKind, it.symbol, it.month, krwConverter.forecast(it)) }
    }

    private fun holdingRows(
        positions: List<HeldPosition>,
        kinds: Map<Long, AccountKind>,
        dividends: List<LedgerEntry>,
        expected: List<Expected>,
    ): List<HoldingRow> {
        val dividendsByHolding = dividends.groupBy { it.accountId to it.symbol }
        val nextPayByHolding = expected.groupBy { it.accountId to it.symbol }
            .mapValues { (_, items) -> items.minOf { it.month } }
        return positions.map { position ->
            val holding = position.holding
            val key = holding.accountId to holding.symbol
            val lastPaidDate = dividendsByHolding[key]?.maxOf { it.settlementDate }
            val lastPaidRows = dividendsByHolding[key]?.filter { it.settlementDate == lastPaidDate }
            HoldingRow(
                accountId = holding.accountId,
                accountKind = kinds.getValue(holding.accountId),
                symbol = holding.symbol,
                name = holding.name,
                lastPaidDate = lastPaidDate,
                lastPaid = lastPaidRows?.map { krwConverter.actual(it) }?.total(),
                perShare = position.profile?.perShare,
                currency = position.profile?.currency ?: holding.currency,
                nextPayMonth = nextPayByHolding[key]?.monthValue,
                profileSource = position.profile?.source,
                needsDividendInfo = position.profile == null,
            )
        }.sortedWith(compareBy<HoldingRow> { it.accountKind }.thenBy { it.symbol }.thenBy { it.accountId })
    }
}

private class Received(val kind: AccountKind, val date: LocalDate, val krw: KrwPair)

private class Expected(
    val accountId: Long,
    val kind: AccountKind,
    val symbol: String,
    val month: YearMonth,
    val krw: KrwPair,
)

private data class ReceivedMonth(val accountId: Long, val symbol: String?, val month: YearMonth)

private fun headline(receivedThisYear: List<Received>, expected: List<Expected>, thisMonth: YearMonth): Headline {
    val received = receivedThisYear.map { it.krw }.total()
    val expectedRestOfYear = expected.filter { it.month.year == thisMonth.year }.map { it.krw }.total()
    return Headline(
        received = received,
        receivedSpendable = receivedThisYear.filter { it.kind.isSpendable() }.map { it.krw }.total(),
        receivedLocked = receivedThisYear.filterNot { it.kind.isSpendable() }.map { it.krw }.total(),
        nextMonthExpected = expected.filter { it.month == thisMonth.plusMonths(1) }.map { it.krw }.total(),
        yearTotalExpected = listOf(received, expectedRestOfYear).total(),
    )
}

private fun months(year: Int, receivedThisYear: List<Received>, expected: List<Expected>): List<MonthBar> {
    val pieces = receivedThisYear.map { YearMonth.from(it.date) to MonthSegment(it.kind, it.krw, true) } +
        expected.map { it.month to MonthSegment(it.kind, it.krw, false) }
    val piecesByMonth = pieces.groupBy({ it.first }, { it.second })
    return Month.entries.map { MonthBar(it.value, mergeSegments(piecesByMonth[YearMonth.of(year, it)].orEmpty())) }
}

private fun mergeSegments(pieces: List<MonthSegment>): List<MonthSegment> = pieces
    .groupBy { it.accountKind to it.actual }
    .map { (key, group) -> MonthSegment(key.first, group.map { it.amount }.total(), key.second) }
    .sortedWith(compareBy<MonthSegment> { it.accountKind }.thenByDescending { it.actual })

private fun AccountKind.isSpendable(): Boolean = when (this) {
    AccountKind.BROKERAGE -> true
    AccountKind.ISA, AccountKind.PENSION_SAVINGS -> false
}

private fun List<KrwPair>.total() = KrwPair(sumOf { it.gross }, sumOf { it.net })

private const val FORECAST_MONTHS = 12L
