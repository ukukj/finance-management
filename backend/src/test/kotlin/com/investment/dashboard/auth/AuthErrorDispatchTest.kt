package com.investment.dashboard.auth

import com.investment.dashboard.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Clock

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class, AuthErrorDispatchTest.FailingLogoutConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class AuthErrorDispatchTest(@Autowired private val rest: RestTestClient) {

    @Test
    @DisplayName("실제 서버에서 로그아웃 처리 중 예상하지 못한 오류가 나면 401 이 아니라 500 이다")
    fun unexpected_error_during_logout_is_500_not_401(output: CapturedOutput) {
        rest.post().uri("/api/auth/logout")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            .expectBody()
            .jsonPath("$.path").isEqualTo("/api/auth/logout")

        assertThat(output).contains("LogoutTestFailure: 테스트용 로그아웃 오류")
    }

    @Test
    @DisplayName("실제 서버에서 토큰 없이 보호 경로를 부르면 본문 없는 401 이다")
    fun protected_path_without_token_is_401_on_real_server() {
        rest.get().uri("/api/sync/latest")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().isEmpty()
    }

    @Test
    @DisplayName("실제 서버에서 토큰 없이 /error 를 직접 부르면 본문 없는 401 이다")
    fun direct_error_path_without_token_is_401_on_real_server() {
        rest.get().uri("/error")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().isEmpty()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FailingLogoutConfiguration {

        @Bean
        @Primary
        fun failingRefreshTokenService(
            repository: RefreshTokenRepository,
            properties: AuthProperties,
            clock: Clock,
        ): RefreshTokenService = FailingLogoutRefreshTokenService(repository, properties, clock)
    }

    open class FailingLogoutRefreshTokenService(
        repository: RefreshTokenRepository,
        properties: AuthProperties,
        clock: Clock,
    ) : RefreshTokenService(repository, properties, clock) {

        override fun revoke(token: String?) = throw LogoutTestFailure()
    }
}

private class LogoutTestFailure : RuntimeException("테스트용 로그아웃 오류")
