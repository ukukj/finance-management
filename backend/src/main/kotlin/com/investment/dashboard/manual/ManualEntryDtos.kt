package com.investment.dashboard.manual

import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

data class ManualDividendRequest(
    @field:Pattern(regexp = "\\S+")
    @field:Size(max = 20)
    val symbol: String,
    val date: LocalDate,
    @field:Positive
    @field:Digits(integer = 16, fraction = 4)
    val amount: BigDecimal,
)

data class DepositRequest(
    val date: LocalDate,
    @field:Positive
    @field:Digits(integer = 16, fraction = 4)
    val amount: BigDecimal,
)

data class LedgerEntryCreated(val id: Long)

data class PendingDividendView(val symbol: String, val month: YearMonth, val expectedKrw: Long)
