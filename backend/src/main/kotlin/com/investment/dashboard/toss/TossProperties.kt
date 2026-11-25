package com.investment.dashboard.toss

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.toss")
data class TossProperties(
    val baseUrl: String,
    val clientId: String,
    val clientSecret: String,
    val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
)

private val DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5)
private val DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30)
