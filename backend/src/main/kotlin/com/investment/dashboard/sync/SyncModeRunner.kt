package com.investment.dashboard.sync

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator

class SyncModeRunner(private val sync: (SyncTrigger) -> SyncRun, triggerEnv: String?) :
    ApplicationRunner,
    ExitCodeGenerator {

    private val trigger = triggerOf(triggerEnv)
    private var code = FAILURE_EXIT_CODE

    override fun run(args: ApplicationArguments) {
        code = exitCodeOf(sync(trigger).status)
    }

    override fun getExitCode(): Int = code
}

private fun triggerOf(value: String?): SyncTrigger {
    if (value == null) {
        return SyncTrigger.SCHEDULED
    }
    return requireNotNull(SyncTrigger.entries.firstOrNull { it.name == value }) {
        "SYNC_TRIGGER 는 SCHEDULED 또는 MANUAL: $value"
    }
}

private fun exitCodeOf(status: SyncStatus): Int = when (status) {
    SyncStatus.SUCCEEDED, SyncStatus.SKIPPED -> SUCCESS_EXIT_CODE
    SyncStatus.RUNNING, SyncStatus.FAILED -> FAILURE_EXIT_CODE
}

private const val SUCCESS_EXIT_CODE = 0
private const val FAILURE_EXIT_CODE = 1
