package com.investment.dashboard.sync

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.ContextClosedEvent
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

@ExtendWith(OutputCaptureExtension::class)
class SyncExecutorConfigTest {

    private val executor = SyncExecutorConfig().syncExecutor()

    @AfterEach
    fun stopExecutor() {
        executor.stop()
    }

    @Test
    @DisplayName("syncExecutor 는 호출 스레드가 아니라 sync-1 스레드에서 실행한다")
    fun sync_executor_runs_task_on_sync_thread() {
        val threadName = CompletableFuture<String>()

        executor.execute { threadName.complete(Thread.currentThread().name) }

        assertThat(threadName.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("sync-1")
    }

    @Test
    @DisplayName("동기화가 돌고 하나가 기다리는 중이면 그다음 요청은 로그 없이 버려서 둘만 실행한다")
    fun request_is_dropped_silently_while_one_sync_runs_and_one_waits(output: CapturedOutput) {
        val runs = CopyOnWriteArrayList<String>()
        val runningStarted = CountDownLatch(1)
        val releaseRunning = CountDownLatch(1)
        val pendingDone = CountDownLatch(1)
        val laterDone = CountDownLatch(1)
        executor.execute {
            runningStarted.countDown()
            releaseRunning.awaitOrFail("running 해제")
            runs += "running"
        }
        runningStarted.awaitOrFail("running 시작")

        executor.execute {
            runs += "pending"
            pendingDone.countDown()
        }
        executor.execute { runs += "dropped" }
        releaseRunning.countDown()
        pendingDone.awaitOrFail("pending 끝")
        executor.execute {
            runs += "later"
            laterDone.countDown()
        }
        laterDone.awaitOrFail("later 끝")

        assertThat(runs).containsExactly("running", "pending", "later")
        assertThat(output).doesNotContain("동기화 요청을 버림")
    }

    @Test
    @DisplayName("멈춘 뒤에 들어온 동기화 요청은 실행하지 않고 경고를 남긴다")
    fun request_after_stop_is_dropped_with_warning(output: CapturedOutput) {
        val runs = CopyOnWriteArrayList<String>()
        executor.stop()

        executor.execute { runs += "after stop" }

        assertThat(runs).isEmpty()
        assertThat(output).contains("앱이 멈추는 중이라 동기화 요청을 버림")
    }

    @Test
    @DisplayName("syncExecutor 는 웹 서버가 멈춘 다음 단계에서 멈춘다")
    fun sync_executor_stops_after_web_server() {
        assertThat(executor.phase).isLessThan(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE)
    }

    @Test
    @DisplayName("컨텍스트를 닫으면 돌고 있는 동기화가 끝난 뒤에 빈을 정리하고 기다리던 동기화는 버린다")
    fun closing_context_waits_for_running_sync_before_destroying_beans_and_drops_pending() {
        val events = CopyOnWriteArrayList<String>()
        val closing = CountDownLatch(1)
        val runningStarted = CountDownLatch(1)
        val context = contextWithSyncExecutor(events, closing)
        val syncExecutor = context.getBean("syncExecutor", SyncExecutor::class.java)
        syncExecutor.execute {
            runningStarted.countDown()
            closing.awaitOrFail("컨텍스트 닫힘")
            Thread.sleep(RUNNING_SYNC_MILLIS)
            events += "동기화 끝"
        }
        runningStarted.awaitOrFail("running 시작")
        syncExecutor.execute { events += "기다리던 동기화" }

        context.close()

        assertThat(events).containsExactly("동기화 끝", "빈 정리")
    }

    @Test
    @DisplayName("컨텍스트를 잠시 멈췄다 다시 켜도 syncExecutor 는 닫히지 않고 동기화를 실행한다")
    fun pausing_and_restarting_context_keeps_sync_executor_open() {
        AnnotationConfigApplicationContext(SyncExecutorConfig::class.java).use { context ->
            val syncExecutor = context.getBean("syncExecutor", SyncExecutor::class.java)
            val ran = CountDownLatch(1)
            context.pause()
            context.restart()

            syncExecutor.execute { ran.countDown() }

            ran.awaitOrFail("다시 켠 뒤 동기화 실행")
        }
    }

    @Test
    @DisplayName("돌고 있는 동기화가 기다리는 시간을 넘기면 경고를 남기고 더 기다리지 않고 멈춘다")
    fun stop_gives_up_after_shutdown_wait(output: CapturedOutput) {
        val shortWait = SyncExecutor(Duration.ofMillis(SHORT_WAIT_MILLIS))
        val runningStarted = CountDownLatch(1)
        shortWait.execute {
            runningStarted.countDown()
            Thread.sleep(LONG_SYNC_MILLIS)
        }
        runningStarted.awaitOrFail("running 시작")

        assertTimeoutPreemptively(Duration.ofSeconds(1)) { shortWait.stop() }

        assertThat(output).contains("동기화가 100ms 안에 끝나지 않아 기다리지 않고 종료")
    }

    @Test
    @DisplayName("멈추는 동안 인터럽트되면 기다리지 않고 경고를 남기며 인터럽트 상태를 되살린다")
    fun interrupt_during_stop_restores_interrupt_flag(output: CapturedOutput) {
        val runningStarted = CountDownLatch(1)
        val releaseRunning = CountDownLatch(1)
        executor.execute {
            runningStarted.countDown()
            releaseRunning.awaitOrFail("running 해제")
        }
        runningStarted.awaitOrFail("running 시작")

        Thread.currentThread().interrupt()
        executor.stop()
        val interruptedAfterStop = Thread.interrupted()
        releaseRunning.countDown()

        assertThat(interruptedAfterStop).isTrue()
        assertThat(output).contains("동기화 종료를 기다리다 인터럽트되어 기다리지 않고 종료")
    }

    private fun contextWithSyncExecutor(events: MutableList<String>, closing: CountDownLatch) =
        AnnotationConfigApplicationContext().apply {
            register(SyncExecutorConfig::class.java)
            registerBean(
                "beanDestroyedBeforeSyncExecutor",
                DisposableBean::class.java,
                Supplier { DisposableBean { events += "빈 정리" } },
                { it.setDependsOn("syncExecutor") },
            )
            addApplicationListener(ApplicationListener<ContextClosedEvent> { closing.countDown() })
            refresh()
        }

    private fun CountDownLatch.awaitOrFail(what: String) {
        assertThat(await(WAIT_SECONDS, TimeUnit.SECONDS)).describedAs("$what 신호를 ${WAIT_SECONDS}초 안에 못 받음").isTrue()
    }
}

private const val WAIT_SECONDS = 5L
private const val RUNNING_SYNC_MILLIS = 200L
private const val SHORT_WAIT_MILLIS = 100L
private const val LONG_SYNC_MILLIS = 2_000L
