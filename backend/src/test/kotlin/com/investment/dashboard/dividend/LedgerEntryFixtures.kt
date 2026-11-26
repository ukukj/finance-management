package com.investment.dashboard.dividend

import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.LedgerEntryType
import com.investment.dashboard.ledger.LedgerSource
import java.math.BigDecimal
import java.time.LocalDate

fun buy(symbol: String, quantity: String, date: String) = trade(LedgerEntryType.BUY, symbol, quantity, date)

fun sell(symbol: String, quantity: String, date: String) = trade(LedgerEntryType.SELL, symbol, quantity, date)

fun transferIn(symbol: String, quantity: String, date: String) =
    trade(LedgerEntryType.SECURITIES_TRANSFER_IN, symbol, quantity, date)

fun transferOut(symbol: String, quantity: String, date: String) =
    trade(LedgerEntryType.SECURITIES_TRANSFER_OUT, symbol, quantity, date)

fun dividend(symbol: String, amount: String, net: String, currency: String, date: String) =
    LedgerEntry(1L, LedgerEntryType.DIVIDEND, currency, LocalDate.parse(date), LedgerSource.TOSS).apply {
        this.symbol = symbol
        this.amount = BigDecimal(amount)
        this.netAmount = BigDecimal(net)
    }

fun dividendOn(symbol: String, date: String) = dividend(symbol, "10.0000", "8.5000", "USD", date)

private fun trade(type: LedgerEntryType, symbol: String, quantity: String, date: String) =
    LedgerEntry(1L, type, "USD", LocalDate.parse(date), LedgerSource.TOSS).apply {
        this.symbol = symbol
        this.quantity = BigDecimal(quantity)
    }
