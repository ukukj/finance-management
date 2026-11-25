package com.investment.dashboard.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LoggingSyncFailureNotifier : SyncFailureNotifier {

    override fun notifyFailure(run: SyncRun) {
        log.warn("토스 동기화 실패 (sync_run {}): {}", run.id, run.failureReason)
    }
}

private val log = LoggerFactory.getLogger(LoggingSyncFailureNotifier::class.java)
