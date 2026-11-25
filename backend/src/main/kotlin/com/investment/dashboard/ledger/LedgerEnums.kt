package com.investment.dashboard.ledger

enum class LedgerEntryType {
    BUY,
    SELL,
    DEPOSIT,
    WITHDRAWAL,
    EXCHANGE_IN,
    EXCHANGE_OUT,
    DIVIDEND,
    INTEREST,
    FEE,
    SECURITIES_TRANSFER_IN,
    SECURITIES_TRANSFER_OUT,
}

enum class LedgerSource { TOSS, MANUAL }
