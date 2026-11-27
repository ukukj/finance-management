package com.investment.dashboard.sync

import com.investment.dashboard.TestcontainersConfiguration
import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.dividend.DividendProfileRepository
import com.investment.dashboard.fx.FxRateRepository
import com.investment.dashboard.holding.HoldingRepository
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.support.TestClockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.OffsetDateTime

@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class, FakeSyncConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class SyncApiTest {

    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var fake: FakeTossApi

    @Autowired
    @Qualifier("syncExecutor")
    lateinit var executor: QueuedExecutor

    @Autowired
    lateinit var syncRunRepository: SyncRunRepository

    @Autowired
    lateinit var accountRepository: AccountRepository

    @Autowired
    lateinit var holdingRepository: HoldingRepository

    @Autowired
    lateinit var ledgerRepository: LedgerEntryRepository

    @Autowired
    lateinit var fxRateRepository: FxRateRepository

    @Autowired
    lateinit var profileRepository: DividendProfileRepository

    private lateinit var rest: RestTestClient

    @BeforeEach
    fun reset() {
        executor.clear()
        profileRepository.deleteAllInBatch()
        ledgerRepository.deleteAllInBatch()
        holdingRepository.deleteAllInBatch()
        accountRepository.deleteAllInBatch()
        fxRateRepository.deleteAllInBatch()
        syncRunRepository.deleteAllInBatch()
        fake.reset()
        val mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        rest = RestTestClient.bindTo(mvc).build()
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/sync 는 202 를 주고 MANUAL 동기화 기록이 하나 생긴다")
    fun post_returns_202_and_records_one_manual_sync() {
        rest.post().uri("/api/sync")
            .exchange()
            .expectStatus().isAccepted()
            .expectBody().isEmpty()
        executor.runQueued()

        assertThat(syncRunRepository.findAll()).singleElement()
            .returns(SyncTrigger.MANUAL) { it.triggerType }
            .returns(SyncStatus.SUCCEEDED) { it.status }
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/sync 는 동기화가 끝나기를 기다리지 않고 202 를 준다")
    fun post_returns_202_without_waiting_for_sync() {
        rest.post().uri("/api/sync")
            .exchange()
            .expectStatus().isAccepted()

        assertThat(fake.calls).isZero()
        assertThat(syncRunRepository.count()).isZero()
    }

    @Test
    @WithMockUser
    @DisplayName("백그라운드 동기화가 예상하지 못한 오류를 던지면 스택 트레이스와 함께 ERROR 로그를 남긴다")
    fun background_unexpected_error_is_logged_at_error_with_stack_trace(output: CapturedOutput) {
        fake.failOnTransactions(BackgroundSyncTestFailure())
        rest.post().uri("/api/sync")
            .exchange()
            .expectStatus().isAccepted()

        executor.runQueued()

        assertThat(output).containsPattern(" ERROR .* 백그라운드 동기화 중 예상하지 못한 오류")
        assertThat(output).contains(
            "BackgroundSyncTestFailure: 테스트용 백그라운드 오류",
            "at com.investment.dashboard.sync.SyncApiTest",
        )
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/sync/latest 는 마지막 실행의 상태와 사유를 준다")
    fun latest_returns_status_and_reason_of_last_run() {
        syncRun(SyncStatus.SUCCEEDED, "2026-11-21T03:00+09:00", "2026-11-21T03:01+09:00", null)
        syncRun(SyncStatus.FAILED, "2026-11-22T03:00+09:00", "2026-11-22T03:00:30+09:00", "동기화 실패: 토스 응답 지연")

        rest.get().uri("/api/sync/latest")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.lastStatus").isEqualTo("FAILED")
            .jsonPath("$.lastFailureReason").isEqualTo("동기화 실패: 토스 응답 지연")
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/sync/latest 는 마지막 실행의 시작 시각을 +09:00 시각으로 준다")
    fun latest_returns_start_time_of_last_run_in_seoul_offset() {
        syncRun(SyncStatus.SUCCEEDED, "2026-11-21T03:00+09:00", "2026-11-21T03:02+09:00", null)
        runningSyncRun("2026-11-22T10:00+09:00")

        rest.get().uri("/api/sync/latest")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.lastStatus").isEqualTo("RUNNING")
            .jsonPath("$.lastStartedAt").isEqualTo("2026-11-22T10:00:00+09:00")
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/sync/latest 의 마지막 성공 시각은 +09:00 시각이다")
    fun latest_returns_last_succeeded_at_in_seoul_offset() {
        syncRun(SyncStatus.SUCCEEDED, "2026-11-21T03:00+09:00", "2026-11-21T03:02+09:00", null)

        rest.get().uri("/api/sync/latest")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.lastSucceededAt").isEqualTo("2026-11-21T03:02:00+09:00")
    }

    @Test
    @DisplayName("API 모드에는 sync 러너가 없어 기동할 때 동기화하지 않는다")
    fun api_mode_has_no_sync_runner() {
        val runners = context.getBeanNamesForType(SyncModeRunner::class.java)

        assertThat(runners).isEmpty()
    }

    @Test
    @DisplayName("로그인 없이 POST /api/sync 는 401 이다")
    fun post_without_login_is_401() {
        rest.post().uri("/api/sync")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().isEmpty()
    }

    private fun syncRun(status: SyncStatus, startedAt: String, finishedAt: String, failureReason: String?) {
        syncRunRepository.save(
            SyncRun(SyncTrigger.SCHEDULED, status, OffsetDateTime.parse(startedAt)).apply {
                this.finishedAt = OffsetDateTime.parse(finishedAt)
                this.failureReason = failureReason
            },
        )
    }

    private fun runningSyncRun(startedAt: String) {
        syncRunRepository.save(SyncRun(SyncTrigger.MANUAL, SyncStatus.RUNNING, OffsetDateTime.parse(startedAt)))
    }
}

private class BackgroundSyncTestFailure : RuntimeException("테스트용 백그라운드 오류")
