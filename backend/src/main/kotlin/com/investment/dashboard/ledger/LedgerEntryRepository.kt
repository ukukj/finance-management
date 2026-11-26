package com.investment.dashboard.ledger

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface LedgerEntryRepository : JpaRepository<LedgerEntry, Long> {
    fun findByAccountIdOrderBySettlementDateAsc(accountId: Long): List<LedgerEntry>

    fun findByTypeAndSettlementDateBetween(type: LedgerEntryType, from: LocalDate, to: LocalDate): List<LedgerEntry>

    fun findByType(type: LedgerEntryType): List<LedgerEntry>

    @Modifying
    @Query("delete from LedgerEntry e where e.accountId = :accountId and e.source = :source")
    fun deleteByAccountIdAndSource(accountId: Long, source: LedgerSource)
}
