package com.investment.dashboard.toss

import org.springframework.stereotype.Component
import java.time.Duration

fun interface Sleeper {
    fun sleep(duration: Duration)
}

@Component
class ThreadSleeper : Sleeper {
    override fun sleep(duration: Duration) = Thread.sleep(duration)
}
