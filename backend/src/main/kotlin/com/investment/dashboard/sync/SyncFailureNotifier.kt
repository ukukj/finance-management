package com.investment.dashboard.sync

interface SyncFailureNotifier {
    fun notifyFailure(run: SyncRun)
}
