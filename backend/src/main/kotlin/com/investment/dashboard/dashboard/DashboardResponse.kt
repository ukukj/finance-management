package com.investment.dashboard.dashboard

import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.dividend.ProfileSource
import com.investment.dashboard.sync.SyncStatusView
import java.math.BigDecimal
import java.time.LocalDate

data class KrwPair(val gross: Long, val net: Long)

data class DashboardResponse(
    val headline: Headline,
    val months: List<MonthBar>,
    val holdings: List<HoldingRow>,
    val sync: SyncStatusView,
)

data class Headline(
    val received: KrwPair,
    val receivedSpendable: KrwPair,
    val receivedLocked: KrwPair,
    val nextMonthExpected: KrwPair,
    val yearTotalExpected: KrwPair,
)

data class MonthBar(val month: Int, val segments: List<MonthSegment>)

data class MonthSegment(val accountKind: AccountKind, val amount: KrwPair, val actual: Boolean)

data class HoldingRow(
    val accountId: Long,
    val accountKind: AccountKind,
    val symbol: String,
    val name: String,
    val lastPaidDate: LocalDate?,
    val lastPaid: KrwPair?,
    val perShare: BigDecimal?,
    val currency: String,
    val nextPayMonth: Int?,
    val profileSource: ProfileSource?,
    val needsDividendInfo: Boolean,
)
