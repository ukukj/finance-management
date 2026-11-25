package com.investment.dashboard.fx

import org.springframework.data.jpa.repository.JpaRepository

interface FxRateRepository : JpaRepository<FxRate, String>
