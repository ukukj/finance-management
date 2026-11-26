package com.investment.dashboard.dividend

import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

data class PutDividendProfileRequest(
    @field:Positive
    @field:Digits(integer = 14, fraction = 6)
    val perShare: BigDecimal,
    val payMonths: List<Int>,
)
