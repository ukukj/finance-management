package com.investment.dashboard.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.SecretKey

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RefreshTokenService(
    private val repository: RefreshTokenRepository,
    private val properties: AuthProperties,
    private val clock: Clock,
) {

    private val random = SecureRandom()

    private val hashKey: SecretKey = properties.jwtKey()

    @Transactional
    fun issue(): IssuedRefreshToken {
        val now = OffsetDateTime.now(clock)
        return save(now, now.plus(properties.refreshTokenTtl))
    }

    @Transactional
    fun rotate(token: String?): IssuedRefreshToken? {
        val stored = token?.let { repository.findForUpdateByTokenHash(hash(it)) } ?: return null
        repository.delete(stored)
        val now = OffsetDateTime.now(clock)
        return if (now.isAfter(stored.expiresAt)) null else save(now, stored.expiresAt)
    }

    @Transactional
    fun revoke(token: String?) {
        token?.let { repository.findForUpdateByTokenHash(hash(it)) }?.let(repository::delete)
    }

    private fun save(now: OffsetDateTime, expiresAt: OffsetDateTime): IssuedRefreshToken {
        repository.deleteExpired(now)
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        repository.save(RefreshToken(hash(token), expiresAt, now))
        return IssuedRefreshToken(token, Duration.between(now, expiresAt))
    }

    private fun hash(token: String): String {
        val mac = Mac.getInstance(hashKey.algorithm)
        mac.init(hashKey)
        return HexFormat.of().formatHex(mac.doFinal(token.toByteArray()))
    }
}

data class IssuedRefreshToken(val value: String, val maxAge: Duration)

private const val TOKEN_BYTES = 32
