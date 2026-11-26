package com.investment.dashboard.dividend

import com.investment.dashboard.account.AccountKind
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.SortedSet

data class AutoProfile(
    val symbol: String,
    val currency: String,
    val perShare: BigDecimal,
    val payMonths: SortedSet<Int>,
    val netRatio: BigDecimal,
    val perShareBasisDate: LocalDate,
)

data class ForecastItem(
    val accountId: Long,
    val accountKind: AccountKind,
    val symbol: String,
    val month: YearMonth,
    val gross: BigDecimal,
    val net: BigDecimal,
    val currency: String,
)
