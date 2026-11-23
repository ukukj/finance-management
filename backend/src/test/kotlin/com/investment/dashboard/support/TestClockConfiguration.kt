package com.investment.dashboard.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.OffsetDateTime
import java.time.ZoneId

@TestConfiguration(proxyBeanMethods = false)
class TestClockConfiguration {

    @Bean
    @Primary
    fun mutableClock(): MutableClock =
        MutableClock(OffsetDateTime.parse("2026-11-22T10:00+09:00").toInstant(), ZoneId.of("Asia/Seoul"))
}
