package com.investment.dashboard.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

class MutableClock private constructor(
    private val now: AtomicReference<Instant>,
    private val start: Instant,
    private val zone: ZoneId,
) : Clock() {

    constructor(start: Instant, zone: ZoneId) : this(AtomicReference(start), start, zone)

    fun set(instant: Instant) {
        now.set(instant)
    }

    fun advance(duration: Duration) {
        now.updateAndGet { it.plus(duration) }
    }

    fun reset() {
        now.set(start)
    }

    override fun instant(): Instant = now.get()

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, start, zone)
}
