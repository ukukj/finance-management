package com.investment.dashboard.dividend

import org.springframework.data.jpa.repository.JpaRepository

interface DividendProfileRepository : JpaRepository<DividendProfile, Long> {
    fun findByAccountId(accountId: Long): List<DividendProfile>

    fun findByAccountIdAndSymbol(accountId: Long, symbol: String): DividendProfile?
}
