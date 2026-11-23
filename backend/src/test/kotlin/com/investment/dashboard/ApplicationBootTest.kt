package com.investment.dashboard

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class ApplicationBootTest(@Autowired private val rest: RestTestClient) {

    @Test
    @DisplayName("헬스 체크는 로그인 없이 UP 을 준다")
    fun health_is_up_without_login() {
        rest.get().uri("/actuator/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP")
    }

    @Test
    @DisplayName("다른 API 는 로그인 없이 부르면 리다이렉트가 아니라 401 이다")
    fun api_returns_401_without_login() {
        rest.get().uri("/api/anything")
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isUnauthorized()
            .expectHeader().doesNotExist(HttpHeaders.LOCATION)
    }
}
