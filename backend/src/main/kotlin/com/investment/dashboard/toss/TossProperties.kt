package com.investment.dashboard.toss

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.toss")
data class TossProperties(val baseUrl: String, val clientId: String, val clientSecret: String)
