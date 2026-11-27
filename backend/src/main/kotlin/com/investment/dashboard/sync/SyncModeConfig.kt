package com.investment.dashboard.sync

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment

@Profile("sync")
@Configuration(proxyBeanMethods = false)
class SyncModeConfig {

    @Bean
    fun syncModeRunner(syncService: TossSyncService, environment: Environment) =
        SyncModeRunner(syncService::run, environment.getProperty("SYNC_TRIGGER"))
}
