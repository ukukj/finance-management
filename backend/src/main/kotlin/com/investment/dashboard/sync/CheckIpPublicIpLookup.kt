package com.investment.dashboard.sync

import org.slf4j.LoggerFactory
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import java.time.Duration

@Component
class CheckIpPublicIpLookup(restClientBuilder: RestClient.Builder) : PublicIpLookup {

    private val restClient = restClientBuilder
        .requestFactory(
            ClientHttpRequestFactoryBuilder.detect().build(
                HttpClientSettings.defaults().withTimeouts(TIMEOUT, TIMEOUT),
            ),
        )
        .build()

    override fun currentIp(): String? = try {
        restClient.get().uri(CHECK_IP_URL).retrieve().body<String>()?.trim()?.ifEmpty { null }
    } catch (e: RestClientException) {
        log.warn("공인 IP 조회 실패", e)
        null
    }
}

private const val CHECK_IP_URL = "https://checkip.amazonaws.com"
private val TIMEOUT = Duration.ofSeconds(3)
private val log = LoggerFactory.getLogger(CheckIpPublicIpLookup::class.java)
