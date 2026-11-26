package com.investment.dashboard.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AccessTokenIssuer(
    private val encoder: JwtEncoder,
    private val properties: AuthProperties,
    private val clock: Clock,
) {

    fun issue(username: String): AccessTokenResponse {
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val expiresAt = issuedAt.plus(properties.accessTokenTtl)
        val claims = JwtClaimsSet.builder()
            .issuer(JWT_ISSUER)
            .subject(username)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return AccessTokenResponse(token, OffsetDateTime.ofInstant(expiresAt, clock.zone))
    }
}
