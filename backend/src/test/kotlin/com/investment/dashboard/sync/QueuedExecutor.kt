package com.investment.dashboard.sync

import java.util.concurrent.Executor

class QueuedExecutor : Executor {

    private val queued = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        queued.addLast(command)
    }

    fun runQueued() {
        while (queued.isNotEmpty()) {
            queued.removeFirst().run()
        }
    }

    fun clear() {
        queued.clear()
    }
}
