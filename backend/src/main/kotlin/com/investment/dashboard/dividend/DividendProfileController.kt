package com.investment.dashboard.dividend

import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class DividendProfileController(private val dividendProfileService: DividendProfileService) {

    @PutMapping("/api/accounts/{id}/dividend-profiles/{symbol}")
    fun put(
        @PathVariable id: Long,
        @PathVariable @Size(max = 20) @Pattern(regexp = "\\S+") symbol: String,
        @Valid @RequestBody request: PutDividendProfileRequest,
    ) {
        dividendProfileService.putProfile(id, symbol, request)
    }
}
