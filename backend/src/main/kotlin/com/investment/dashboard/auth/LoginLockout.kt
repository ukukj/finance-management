package com.investment.dashboard.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class LoginLockout(
    private val repository: LoginAttemptRepository,
    private val properties: AuthProperties,
    private val clock: Clock,
) {

    private val passwordEncoder: PasswordEncoder = BCryptPasswordEncoder()

    @Transactional
    fun attempt(username: String, password: String): LoginResult {
        val attempt = checkNotNull(repository.findForUpdateById(LOGIN_ATTEMPT_ID)) { "login_attempt 행 없음" }
        val now = OffsetDateTime.now(clock)
        val lockedUntil = attempt.lockedUntil
        return when {
            lockedUntil != null && now.isBefore(lockedUntil) ->
                LoginResult.Locked(lockedUntil.atZoneSameInstant(clock.zone).toOffsetDateTime())

            matches(username, password) -> succeed(attempt)

            else -> fail(attempt, now)
        }
    }

    private fun succeed(attempt: LoginAttempt): LoginResult {
        attempt.failedCount = 0
        attempt.lockedUntil = null
        return LoginResult.Success
    }

    private fun fail(attempt: LoginAttempt, now: OffsetDateTime): LoginResult {
        attempt.failedCount += 1
        if (attempt.failedCount >= properties.maxFailures) {
            attempt.failedCount = 0
            attempt.lockedUntil = now.plusMinutes(properties.lockMinutes)
        }
        return LoginResult.Failure
    }

    private fun matches(username: String, password: String): Boolean {
        val passwordMatches = passwordEncoder.matches(password, properties.passwordHash)
        val usernameMatches = MessageDigest.isEqual(username.toByteArray(), properties.username.toByteArray())
        return passwordMatches && usernameMatches
    }
}

sealed interface LoginResult {
    data object Success : LoginResult

    data object Failure : LoginResult

    data class Locked(val until: OffsetDateTime) : LoginResult
}

private const val LOGIN_ATTEMPT_ID: Short = 1
