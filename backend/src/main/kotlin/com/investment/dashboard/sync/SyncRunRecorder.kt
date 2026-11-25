package com.investment.dashboard.sync

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

@Component
class SyncRunRecorder(private val syncRunRepository: SyncRunRepository, private val clock: Clock) {

    fun start(trigger: SyncTrigger): SyncRun =
        syncRunRepository.save(SyncRun(trigger, SyncStatus.RUNNING, OffsetDateTime.now(clock)))

    fun finish(run: SyncRun, status: SyncStatus, failureReason: String?): SyncRun {
        run.status = status
        run.failureReason = failureReason?.take(MAX_FAILURE_REASON_LENGTH)
        run.finishedAt = OffsetDateTime.now(clock)
        return syncRunRepository.save(run)
    }
}

private const val MAX_FAILURE_REASON_LENGTH = 500
