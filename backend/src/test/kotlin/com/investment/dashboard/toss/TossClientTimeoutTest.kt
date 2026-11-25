package com.investment.dashboard.toss

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TossClientTimeoutTest {

    private val release = CountDownLatch(1)
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            release.await(5, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        start()
    }

    @AfterEach
    fun stopServer() {
        release.countDown()
        server.stop(0)
    }

    @Test
    @DisplayName("토스가 설정한 읽기 타임아웃 안에 응답하지 않으면 읽기 타임아웃으로 실패한다")
    fun fails_with_read_timeout_when_toss_does_not_respond() {
        val properties = bind(
            mapOf(
                "app.toss.base-url" to "http://${server.address.address.hostAddress}:${server.address.port}",
                "app.toss.client-id" to "test-client-id",
                "app.toss.client-secret" to "test-client-secret",
                "app.toss.read-timeout" to "200ms",
            ),
        )
        val client = TossClientConfig().tossClient(RestClient.builder(), properties, Sleeper {})

        val error = catchThrowable { client.accounts() }

        assertThat(error).isInstanceOf(TossApiException::class.java)
            .hasCauseExactlyInstanceOf(ResourceAccessException::class.java)
            .hasRootCauseExactlyInstanceOf(HttpTimeoutException::class.java)
    }

    @Test
    @DisplayName("타임아웃을 설정하지 않으면 연결 5초, 읽기 30초다")
    fun timeouts_default_to_5s_connect_and_30s_read() {
        val properties = bind(
            mapOf(
                "app.toss.base-url" to "https://toss.test",
                "app.toss.client-id" to "test-client-id",
                "app.toss.client-secret" to "test-client-secret",
            ),
        )

        assertThat(properties.connectTimeout).isEqualTo(Duration.ofSeconds(5))
        assertThat(properties.readTimeout).isEqualTo(Duration.ofSeconds(30))
    }

    private fun bind(values: Map<String, String>): TossProperties =
        Binder(MapConfigurationPropertySource(values)).bindOrCreate("app.toss", TossProperties::class.java)
}
