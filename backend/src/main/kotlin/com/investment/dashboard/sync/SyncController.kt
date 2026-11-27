package com.investment.dashboard.sync

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class SyncController(private val syncLauncher: SyncLauncher, private val syncStatusReader: SyncStatusReader) {

    @PostMapping("/api/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun sync() {
        syncLauncher.launch(SyncTrigger.MANUAL)
    }

    @GetMapping("/api/sync/latest")
    fun latest(): SyncStatusView = syncStatusReader.latest()
}
