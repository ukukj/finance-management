package com.investment.dashboard.dashboard

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class DashboardController(private val dashboardService: DashboardService) {

    @GetMapping("/api/dashboard")
    fun dashboard(): DashboardResponse = dashboardService.build()
}
