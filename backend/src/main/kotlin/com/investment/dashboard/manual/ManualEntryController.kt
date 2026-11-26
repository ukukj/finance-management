package com.investment.dashboard.manual

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class ManualEntryController(
    private val manualEntryService: ManualEntryService,
    private val pendingDividendService: PendingDividendService,
) {

    @PostMapping("/api/accounts/{id}/dividends")
    @ResponseStatus(HttpStatus.CREATED)
    fun addDividend(@PathVariable id: Long, @Valid @RequestBody request: ManualDividendRequest): LedgerEntryCreated =
        LedgerEntryCreated(manualEntryService.addDividend(id, request))

    @PostMapping("/api/accounts/{id}/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    fun addDeposit(@PathVariable id: Long, @Valid @RequestBody request: DepositRequest): LedgerEntryCreated =
        LedgerEntryCreated(manualEntryService.addDeposit(id, request))

    @GetMapping("/api/accounts/{id}/pending-dividends")
    fun pendingDividends(@PathVariable id: Long): List<PendingDividendView> =
        pendingDividendService.pendingDividends(id)

    @DeleteMapping("/api/ledger-entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) {
        manualEntryService.deleteEntry(id)
    }
}
