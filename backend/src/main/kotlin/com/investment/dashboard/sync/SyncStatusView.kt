package com.investment.dashboard.sync

import org.springframework.stereotype.Component
import java.time.OffsetDateTime

data class SyncStatusView(
    val lastSucceededAt: OffsetDateTime?,
    val lastStatus: SyncStatus?,
    val lastFailureReason: String?,
)

@Component
class SyncStatusReader(private val syncRunRepository: SyncRunRepository) {

    fun latest(): SyncStatusView {
        val latest = syncRunRepository.findTopByOrderByStartedAtDesc()
        return SyncStatusView(
            lastSucceededAt = syncRunRepository.findTopByStatusOrderByStartedAtDesc(SyncStatus.SUCCEEDED)?.finishedAt,
            lastStatus = latest?.status,
            lastFailureReason = latest?.failureReason,
        )
    }
}
