package com.investment.dashboard.ledger

import java.math.BigDecimal
import java.time.LocalDate

fun manualDeposit(accountId: Long, date: LocalDate, amount: BigDecimal): LedgerEntry {
    val entry = LedgerEntry(accountId, LedgerEntryType.DEPOSIT, KRW, date, LedgerSource.MANUAL)
    entry.amount = amount
    entry.netAmount = amount
    return entry
}

private const val KRW = "KRW"
