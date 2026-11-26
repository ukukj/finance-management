package com.investment.dashboard.manual

import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.dividend.DividendEstimator
import com.investment.dashboard.dividend.DividendProfile
import com.investment.dashboard.dividend.ForecastItem
import com.investment.dashboard.holding.Holding
import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.isValidDividend
import java.time.LocalDate
import java.time.YearMonth

object PendingDividends {

    fun of(
        kind: AccountKind,
        holdings: List<Holding>,
        profiles: List<DividendProfile>,
        entries: List<LedgerEntry>,
        today: LocalDate,
    ): List<ForecastItem> {
        val received = entries.filter { it.isValidDividend(today) }
            .map { it.symbol to YearMonth.from(it.settlementDate) }
            .toSet()
        val profileBySymbol = profiles.associateBy { it.symbol }
        val from = YearMonth.of(today.year, 1)
        val to = YearMonth.from(today)
        return holdings
            .flatMap { holding ->
                profileBySymbol[holding.symbol]
                    ?.let { DividendEstimator.forecast(holding, kind, it, from, to) }
                    .orEmpty()
            }
            .filterNot { (it.symbol to it.month) in received }
            .sortedWith(compareBy<ForecastItem> { it.month }.thenBy { it.symbol })
    }
}
