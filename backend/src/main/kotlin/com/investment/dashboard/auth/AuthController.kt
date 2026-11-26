package com.investment.dashboard.auth

import com.investment.dashboard.common.ApiError
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AuthController(
    private val lockout: LoginLockout,
    private val refreshTokens: RefreshTokenService,
    private val accessTokens: AccessTokenIssuer,
    private val properties: AuthProperties,
    @Value("\${app.cookie.secure}") private val cookieSecure: Boolean,
) {

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<Any> =
        when (val result = lockout.attempt(request.username, request.password)) {
            LoginResult.Success -> tokens(refreshTokens.issue())

            LoginResult.Failure -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError("BAD_CREDENTIALS", "아이디 또는 비밀번호가 틀림"))

            is LoginResult.Locked -> ResponseEntity.status(HttpStatus.LOCKED)
                .body(LoginLockedError("LOGIN_LOCKED", "로그인 실패가 많아 잠김", result.until))
        }

    @PostMapping("/refresh")
    fun refresh(@CookieValue(RefreshCookie.NAME, required = false) token: String?): ResponseEntity<Any> {
        val rotated = refreshTokens.rotate(token)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError("INVALID_REFRESH_TOKEN", "로그인 유지 토큰이 없거나 만료됨"))
        return tokens(rotated)
    }

    @PostMapping("/logout")
    fun logout(@CookieValue(RefreshCookie.NAME, required = false) token: String?): ResponseEntity<Unit> {
        refreshTokens.revoke(token)
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, RefreshCookie.deleted(cookieSecure).toString())
            .build()
    }

    @GetMapping("/me")
    fun me(authentication: Authentication): MeResponse = MeResponse(authentication.name)

    private fun tokens(refreshToken: IssuedRefreshToken): ResponseEntity<Any> {
        val cookie = RefreshCookie.issued(refreshToken.value, refreshToken.maxAge, cookieSecure)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(accessTokens.issue(properties.username))
    }
}
