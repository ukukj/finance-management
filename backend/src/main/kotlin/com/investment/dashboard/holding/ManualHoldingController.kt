package com.investment.dashboard.holding

import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class ManualHoldingController(private val manualHoldingService: ManualHoldingService) {

    @PutMapping("/api/accounts/{id}/holdings/{symbol}")
    fun put(
        @PathVariable id: Long,
        @PathVariable @Size(max = 20) @Pattern(regexp = "\\S+") symbol: String,
        @Valid @RequestBody request: PutHoldingRequest,
    ) {
        manualHoldingService.put(id, symbol, request)
    }

    @DeleteMapping("/api/accounts/{id}/holdings/{symbol}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long, @PathVariable symbol: String) {
        manualHoldingService.delete(id, symbol)
    }
}
