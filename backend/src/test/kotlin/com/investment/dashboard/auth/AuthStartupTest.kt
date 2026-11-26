package com.investment.dashboard.auth

import com.investment.dashboard.support.TestLogin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

class AuthStartupTest {

    private val webRunner = WebApplicationContextRunner()
        .withUserConfiguration(AuthPropertiesBinding::class.java, JwtConfig::class.java)
        .withBean(Clock::class.java, { testClock })
        .withPropertyValues(*completeSettings)

    @Test
    @DisplayName("APP_LOGIN_USERNAME 이 비면 \"APP_LOGIN_USERNAME 없음\" 으로 기동을 거부한다")
    fun refuses_to_start_without_username() {
        webRunner.withPropertyValues("app.auth.username=").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessage("APP_LOGIN_USERNAME 없음")
        }
    }

    @Test
    @DisplayName("APP_LOGIN_PASSWORD_HASH 가 비면 \"APP_LOGIN_PASSWORD_HASH 없음\" 으로 기동을 거부한다")
    fun refuses_to_start_without_password_hash() {
        webRunner.withPropertyValues("app.auth.password-hash=").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessage("APP_LOGIN_PASSWORD_HASH 없음")
        }
    }

    @Test
    @DisplayName("APP_JWT_SECRET 이 비면 \"APP_JWT_SECRET 없음\" 으로 기동을 거부한다")
    fun refuses_to_start_without_jwt_secret() {
        webRunner.withPropertyValues("app.auth.jwt-secret=").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessage("APP_JWT_SECRET 없음")
        }
    }

    @Test
    @DisplayName("APP_JWT_SECRET 이 31바이트면 \"APP_JWT_SECRET 은 32바이트 이상\" 으로 기동을 거부한다")
    fun refuses_to_start_with_31_byte_jwt_secret() {
        val shortSecret = base64("test-key-31-bytes-not-a-secret!")

        webRunner.withPropertyValues("app.auth.jwt-secret=$shortSecret").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessage("APP_JWT_SECRET 은 32바이트 이상")
        }
    }

    @Test
    @DisplayName("APP_JWT_SECRET 이 base64 가 아니면 비밀값 글자를 드러내지 않고 \"APP_JWT_SECRET 은 base64\" 로 기동을 거부한다")
    fun refuses_to_start_with_non_base64_jwt_secret_without_leaking_it() {
        webRunner.withPropertyValues("app.auth.jwt-secret=$NON_BASE64_SECRET").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessage("APP_JWT_SECRET 은 base64")
            assertThat(failureMessages(context.startupFailure))
                .doesNotContain("Illegal base64 character")
                .doesNotContain("5f")
                .doesNotContain(NON_BASE64_SECRET)
        }
    }

    @Test
    @DisplayName("APP_LOGIN_PASSWORD_HASH 가 BCrypt 형식이 아니면 \"APP_LOGIN_PASSWORD_HASH 는 BCrypt\" 로 기동을 거부한다")
    fun refuses_to_start_with_non_bcrypt_password_hash() {
        webRunner.withPropertyValues("app.auth.password-hash={bcrypt}${TestLogin.PASSWORD_HASH}").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessage("APP_LOGIN_PASSWORD_HASH 는 BCrypt")
        }
    }

    @Test
    @DisplayName("APP_JWT_SECRET 이 32바이트면 기동한다")
    fun starts_with_32_byte_jwt_secret() {
        val minimumSecret = base64("test-key-32-bytes-not-a-secret!!")

        webRunner.withPropertyValues("app.auth.jwt-secret=$minimumSecret").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(JwtDecoder::class.java)
        }
    }

    @Test
    @DisplayName("웹이 아닌 sync 모드는 로그인 설정이 비어도 기동하고 인증 빈을 만들지 않는다")
    fun non_web_sync_mode_starts_without_login_settings() {
        ApplicationContextRunner()
            .withUserConfiguration(
                AuthPropertiesBinding::class.java,
                JwtConfig::class.java,
                SecurityConfig::class.java,
                AccessTokenIssuer::class.java,
                LoginLockout::class.java,
                RefreshTokenService::class.java,
                AuthController::class.java,
            )
            .withBean(Clock::class.java, { testClock })
            .withPropertyValues(*completeSettings)
            .withPropertyValues("app.auth.username=", "app.auth.password-hash=", "app.auth.jwt-secret=")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(JwtDecoder::class.java)
                assertThat(context).doesNotHaveBean(AuthController::class.java)
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties::class)
    class AuthPropertiesBinding

    private companion object {
        val testClock: Clock = Clock.fixed(Instant.parse("2026-11-22T01:00:00Z"), ZoneId.of("Asia/Seoul"))

        val completeSettings = arrayOf(
            "app.auth.username=owner",
            "app.auth.password-hash=${TestLogin.PASSWORD_HASH}",
            "app.auth.jwt-secret=${TestLogin.JWT_SECRET}",
            "app.auth.access-token-ttl=30m",
            "app.auth.refresh-token-ttl=30d",
            "app.auth.max-failures=5",
            "app.auth.lock-minutes=15",
            "app.cookie.secure=true",
        )

        const val NON_BASE64_SECRET = "test_only_jwt_key_with_underscores_not_base64"

        fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

        fun failureMessages(failure: Throwable?): String =
            generateSequence(failure) { it.cause }.joinToString("\n") { it.message.orEmpty() }
    }
}
