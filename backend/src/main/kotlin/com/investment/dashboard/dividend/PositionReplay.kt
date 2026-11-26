package com.investment.dashboard.dividend

import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.LedgerEntryType
import java.math.BigDecimal
import java.time.LocalDate

object PositionReplay {

    fun quantityAt(entries: List<LedgerEntry>, symbol: String, date: LocalDate): BigDecimal = entries
        .filter { it.symbol == symbol && !it.isCancellation && !it.settlementDate.isAfter(date) }
        .mapNotNull { it.signedQuantity() }
        .fold(BigDecimal.ZERO, BigDecimal::add)
}

private fun LedgerEntry.signedQuantity(): BigDecimal? = when (type) {
    LedgerEntryType.BUY, LedgerEntryType.SECURITIES_TRANSFER_IN -> requiredQuantity()
    LedgerEntryType.SELL, LedgerEntryType.SECURITIES_TRANSFER_OUT -> requiredQuantity().negate()
    else -> null
}

private fun LedgerEntry.requiredQuantity(): BigDecimal =
    checkNotNull(quantity) { "수량 없는 $type 행: $symbol $settlementDate" }
