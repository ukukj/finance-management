package com.investment.dashboard.auth

import java.time.OffsetDateTime

data class LoginRequest(val username: String, val password: String)

data class AccessTokenResponse(val accessToken: String, val expiresAt: OffsetDateTime)

data class MeResponse(val username: String)

data class LoginLockedError(val code: String, val message: String, val lockedUntil: OffsetDateTime)
