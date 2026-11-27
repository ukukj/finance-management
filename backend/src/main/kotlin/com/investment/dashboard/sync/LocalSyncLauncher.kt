package com.investment.dashboard.sync

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.support.DelegatingErrorHandlingRunnable
import org.springframework.stereotype.Component
import org.springframework.util.ErrorHandler
import java.util.concurrent.Executor

@Component
@ConditionalOnProperty(name = ["app.sync.launcher"], havingValue = "local", matchIfMissing = true)
class LocalSyncLauncher(
    @Qualifier("syncExecutor") private val executor: Executor,
    private val syncService: TossSyncService,
) : SyncLauncher {

    override fun launch(trigger: SyncTrigger) {
        executor.execute(DelegatingErrorHandlingRunnable({ syncService.run(trigger) }, logUnexpectedError))
    }
}

private val log = LoggerFactory.getLogger(LocalSyncLauncher::class.java)
private val logUnexpectedError = ErrorHandler { log.error("백그라운드 동기화 중 예상하지 못한 오류", it) }
