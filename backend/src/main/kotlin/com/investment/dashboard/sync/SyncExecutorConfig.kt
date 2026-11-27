package com.investment.dashboard.sync

import org.slf4j.LoggerFactory
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Configuration(proxyBeanMethods = false)
class SyncExecutorConfig {

    @Bean(defaultCandidate = false)
    fun syncExecutor() = SyncExecutor(SHUTDOWN_WAIT)
}

class SyncExecutor(private val shutdownWait: Duration) :
    Executor,
    SmartLifecycle {

    private val pool = ThreadPoolExecutor(
        RUNNING_LIMIT,
        RUNNING_LIMIT,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(PENDING_LIMIT),
        Thread.ofPlatform().name("sync-", 1).factory(),
        warnWhenShutDown,
    )

    override fun execute(command: Runnable) {
        pool.execute(command)
    }

    override fun start() = Unit

    override fun isRunning(): Boolean = !pool.isShutdown

    override fun isPauseable(): Boolean = false

    override fun getPhase(): Int = PHASE_AFTER_WEB_SERVER

    override fun stop() {
        pool.shutdown()
        pool.queue.clear()
        try {
            if (!pool.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("동기화가 {}ms 안에 끝나지 않아 기다리지 않고 종료", shutdownWait.toMillis())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("동기화 종료를 기다리다 인터럽트되어 기다리지 않고 종료", e)
        }
    }
}

private val warnWhenShutDown = RejectedExecutionHandler { _, pool ->
    if (pool.isShutdown) {
        log.warn("앱이 멈추는 중이라 동기화 요청을 버림")
    }
}

private const val RUNNING_LIMIT = 1
private const val PENDING_LIMIT = 1
private const val PHASE_AFTER_WEB_SERVER = WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1
private val SHUTDOWN_WAIT = Duration.ofSeconds(30)
private val log = LoggerFactory.getLogger(SyncExecutor::class.java)
