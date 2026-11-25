package com.investment.dashboard.holding

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface HoldingRepository : JpaRepository<Holding, Long> {
    fun findByAccountId(accountId: Long): List<Holding>

    fun findByAccountIdAndSymbol(accountId: Long, symbol: String): Holding?

    @Modifying
    @Query("delete from Holding h where h.accountId = :accountId")
    fun deleteByAccountId(accountId: Long)
}
