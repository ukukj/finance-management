package com.investment.dashboard.toss

import java.math.BigDecimal

interface TossApi {
    fun accounts(): List<TossAccount>
    fun holdings(accountSeq: Long): List<TossHolding>
    fun allTransactions(accountSeq: Long): List<TossTransaction>
    fun usdKrwRate(): BigDecimal
}
