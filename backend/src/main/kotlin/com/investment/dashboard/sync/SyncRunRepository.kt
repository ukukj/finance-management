package com.investment.dashboard.sync

import org.springframework.data.jpa.repository.JpaRepository

interface SyncRunRepository : JpaRepository<SyncRun, Long> {
    fun findTopByOrderByStartedAtDesc(): SyncRun?

    fun findTopByStatusOrderByStartedAtDesc(status: SyncStatus): SyncRun?
}
