package com.investment.dashboard.sync

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration(proxyBeanMethods = false)
class FakeSyncConfiguration {

    @Bean
    @Primary
    fun fakeTossApi() = FakeTossApi()

    @Bean
    @Primary
    fun fakePublicIpLookup() = FakePublicIpLookup()

    @Bean
    @Primary
    fun recordingSyncFailureNotifier() = RecordingSyncFailureNotifier()

    @Bean(defaultCandidate = false)
    @Primary
    @Qualifier("syncExecutor")
    fun queuedSyncExecutor() = QueuedExecutor()
}
