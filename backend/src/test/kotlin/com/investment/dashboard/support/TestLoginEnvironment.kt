package com.investment.dashboard.support

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.util.Base64

class TestLoginEnvironment : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val variables = mapOf(
            "APP_LOGIN_USERNAME" to TestLogin.USERNAME,
            "APP_LOGIN_PASSWORD_HASH" to TestLogin.PASSWORD_HASH,
            "APP_JWT_SECRET" to TestLogin.JWT_SECRET,
        )
        environment.propertySources.addBefore(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            MapPropertySource("testLoginEnvironment", variables),
        )
    }
}

object TestLogin {
    const val USERNAME = "owner"
    const val PASSWORD_HASH = "\$2a\$10\$j2V9dDyw18s1xu1.vsqfIuItiAu4AScg5K143camUpmcEADYCXHbW"
    val JWT_SECRET: String = base64("test-only-jwt-signing-key-not-a-secret-long-enough-for-hs512-use")

    private fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())
}
