package com.investment.dashboard

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<DashboardApplication>().with(TestcontainersConfiguration::class).run(*args)
}
