package com.investment.dashboard.sync

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

data class SyncStatusView(
    val lastSucceededAt: OffsetDateTime?,
    val lastStatus: SyncStatus?,
    val lastStartedAt: OffsetDateTime?,
    val lastFailureReason: String?,
)

@Component
class SyncStatusReader(private val syncRunRepository: SyncRunRepository, private val clock: Clock) {

    fun latest(): SyncStatusView {
        val latest = syncRunRepository.findTopByOrderByStartedAtDesc()
        val lastSucceeded = syncRunRepository.findTopByStatusOrderByStartedAtDesc(SyncStatus.SUCCEEDED)
        return SyncStatusView(
            lastSucceededAt = lastSucceeded?.finishedAt?.inClockZone(),
            lastStatus = latest?.status,
            lastStartedAt = latest?.startedAt?.inClockZone(),
            lastFailureReason = latest?.failureReason,
        )
    }

    private fun OffsetDateTime.inClockZone(): OffsetDateTime = atZoneSameInstant(clock.zone).toOffsetDateTime()
}
