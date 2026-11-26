package com.investment.dashboard.taxcard

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class TaxCardController(private val taxCardService: TaxCardService) {

    @GetMapping("/api/tax-cards")
    fun taxCards(): TaxCardResponse = taxCardService.build()
}
