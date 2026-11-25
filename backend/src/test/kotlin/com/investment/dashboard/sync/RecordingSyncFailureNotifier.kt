package com.investment.dashboard.sync

class RecordingSyncFailureNotifier : SyncFailureNotifier {

    val notified = mutableListOf<SyncRun>()

    override fun notifyFailure(run: SyncRun) {
        notified += run
    }
}
