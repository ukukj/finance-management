package com.investment.dashboard.toss

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration(proxyBeanMethods = false)
class TossClientConfig {

    @Bean
    fun tossClient(restClientBuilder: RestClient.Builder, properties: TossProperties, sleeper: Sleeper): TossClient {
        val settings = HttpClientSettings.defaults().withTimeouts(properties.connectTimeout, properties.readTimeout)
        val requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings)
        return TossClient(restClientBuilder.requestFactory(requestFactory), properties, sleeper)
    }
}
