package com.investment.dashboard.auth

import org.springframework.http.ResponseCookie
import java.time.Duration

object RefreshCookie {
    const val NAME = "REFRESH_TOKEN"

    fun issued(token: String, maxAge: Duration, secure: Boolean): ResponseCookie =
        builder(token, secure).maxAge(maxAge).build()

    fun deleted(secure: Boolean): ResponseCookie = builder("", secure).maxAge(Duration.ZERO).build()

    private fun builder(value: String, secure: Boolean): ResponseCookie.ResponseCookieBuilder =
        ResponseCookie.from(NAME, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .path("/api/auth")
}
