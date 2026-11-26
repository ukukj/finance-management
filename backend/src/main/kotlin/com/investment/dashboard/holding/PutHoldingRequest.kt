package com.investment.dashboard.holding

import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class PutHoldingRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    @field:PositiveOrZero
    @field:Digits(integer = 14, fraction = 6)
    val quantity: BigDecimal,
)
