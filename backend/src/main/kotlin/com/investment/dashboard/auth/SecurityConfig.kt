package com.investment.dashboard.auth

import jakarta.servlet.DispatcherType
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity, environment: Environment): SecurityFilterChain {
        val publicAuthPaths = publicAuthPaths()
        http {
            authorizeHttpRequests {
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ERROR), permitAll)
                authorize(publicAuthPaths, permitAll)
                authorize("/actuator/health/**", permitAll)
                if (environment.matchesProfiles("local")) {
                    authorize("/v3/api-docs/**", permitAll)
                }
                authorize(anyRequest, authenticated)
            }
            csrf { disable() }
            sessionManagement {
                sessionCreationPolicy = SessionCreationPolicy.STATELESS
            }
            formLogin { disable() }
            httpBasic { disable() }
            oauth2ResourceServer {
                bearerTokenResolver = headerTokenOutside(publicAuthPaths)
                jwt { }
            }
            exceptionHandling {
                authenticationEntryPoint = HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
            }
        }
        http.oauth2ResourceServer { resourceServer ->
            resourceServer.protectedResourceMetadata { metadata ->
                metadata.protectedResourceMetadataCustomizer { it.tlsClientCertificateBoundAccessTokens(false) }
            }
        }
        return http.build()
    }
}

private fun publicAuthPaths(): RequestMatcher = OrRequestMatcher(
    listOf("/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
        .map { PathPatternRequestMatcher.withDefaults().matcher(it) },
)

private fun headerTokenOutside(paths: RequestMatcher): BearerTokenResolver {
    val headerResolver = DefaultBearerTokenResolver()
    return BearerTokenResolver { request -> if (paths.matches(request)) null else headerResolver.resolve(request) }
}
