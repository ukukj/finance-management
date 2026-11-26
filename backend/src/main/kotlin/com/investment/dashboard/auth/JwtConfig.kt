package com.investment.dashboard.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.time.Duration
import javax.crypto.SecretKey

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class JwtConfig(properties: AuthProperties) {

    private val key: SecretKey

    init {
        properties.requireLoginSettings()
        key = properties.jwtKey()
    }

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build()

    @Bean
    fun jwtDecoder(clock: Clock): JwtDecoder {
        val timestamps = JwtTimestampValidator(Duration.ZERO)
        timestamps.setClock(clock)
        timestamps.setAllowEmptyExpiryClaim(false)
        val decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(timestamps, JwtIssuerValidator(JWT_ISSUER)))
        return decoder
    }
}

internal const val JWT_ISSUER = "investment-dashboard"
