package com.investment.dashboard.toss

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

data class TossAccount(val accountNo: String, val accountSeq: Long, val accountType: String)

data class TossHolding(
    val symbol: String,
    val name: String,
    val marketCountry: String,
    val currency: String,
    val quantity: BigDecimal,
    val averagePurchasePrice: BigDecimal?,
)

// 증권 입출고 행은 amount·netAmount 가 null
data class TossTransaction(
    val transactionType: String,
    val marketCountry: String,
    val currency: String,
    val symbol: String?,
    val assetClass: String?,
    val isinCode: String?,
    val quantity: BigDecimal?,
    val filledPrice: BigDecimal?,
    val amount: BigDecimal?,
    val commission: BigDecimal,
    val tax: BigDecimal,
    val netAmount: BigDecimal?,
    val exchangeRate: BigDecimal?,
    val settlementDate: LocalDate,
    val settled: Boolean,
    val transactionTime: OffsetDateTime?,
    val isCancellation: Boolean,
    val rawJson: String,
)
