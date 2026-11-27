package com.investment.dashboard

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@SpringBootApplication
@ConfigurationPropertiesScan
class DashboardApplication

fun main(args: Array<String>) {
    runDashboard(args)?.let { exitProcess(it) }
}

fun runDashboard(args: Array<String>): Int? {
    val context = runApplication<DashboardApplication>(*args)
    return if (context.environment.matchesProfiles("sync")) SpringApplication.exit(context) else null
}
