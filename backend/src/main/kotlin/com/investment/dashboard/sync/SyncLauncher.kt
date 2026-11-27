package com.investment.dashboard.sync

interface SyncLauncher {
    fun launch(trigger: SyncTrigger)
}
