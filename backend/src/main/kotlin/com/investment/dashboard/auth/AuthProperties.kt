package com.investment.dashboard.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("app.auth")
data class AuthProperties(
    val username: String,
    val passwordHash: String,
    val jwtSecret: String,
    val accessTokenTtl: Duration,
    val refreshTokenTtl: Duration,
    val maxFailures: Int,
    val lockMinutes: Long,
) {
    fun requireLoginSettings() {
        require(username.isNotBlank()) { "APP_LOGIN_USERNAME 없음" }
        require(passwordHash.isNotBlank()) { "APP_LOGIN_PASSWORD_HASH 없음" }
        require(BCRYPT_HASH.matches(passwordHash)) { "APP_LOGIN_PASSWORD_HASH 는 BCrypt" }
        jwtKey()
    }

    fun jwtKey(): SecretKey {
        require(jwtSecret.isNotBlank()) { "APP_JWT_SECRET 없음" }
        require(BASE64.matches(jwtSecret)) { "APP_JWT_SECRET 은 base64" }
        val bytes = Base64.getDecoder().decode(jwtSecret)
        require(bytes.size >= MIN_JWT_KEY_BYTES) { "APP_JWT_SECRET 은 32바이트 이상" }
        return SecretKeySpec(bytes, "HmacSHA256")
    }
}

private const val MIN_JWT_KEY_BYTES = 32

private val BCRYPT_HASH = Regex("""\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}""")

private val BASE64 = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}(?:==)?|[A-Za-z0-9+/]{3}=?)?")
