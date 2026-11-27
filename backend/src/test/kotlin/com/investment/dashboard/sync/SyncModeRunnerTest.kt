package com.investment.dashboard.sync

import com.investment.dashboard.DashboardApplication
import com.investment.dashboard.TestcontainersConfiguration
import com.investment.dashboard.runDashboard
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.context.WebApplicationContext
import java.time.OffsetDateTime

class SyncModeRunnerTest {

    @Test
    @DisplayName("sync 프로필 실행 결과가 FAILED 면 종료 코드는 1 이다")
    fun exit_code_one_on_failure() {
        val runner = SyncModeRunner({ failedRun() }, "SCHEDULED")

        runner.run(DefaultApplicationArguments())

        assertThat(runner.exitCode).isEqualTo(1)
    }

    @Test
    @DisplayName("SUCCEEDED 면 종료 코드 0 이다")
    fun exit_code_zero_on_success() {
        val runner = SyncModeRunner({ syncRun(SyncStatus.SUCCEEDED) }, "SCHEDULED")

        runner.run(DefaultApplicationArguments())

        assertThat(runner.exitCode).isEqualTo(0)
    }

    @Test
    @DisplayName("SKIPPED 면 종료 코드 0 이다")
    fun exit_code_zero_on_skipped() {
        val runner = SyncModeRunner({ syncRun(SyncStatus.SKIPPED) }, "SCHEDULED")

        runner.run(DefaultApplicationArguments())

        assertThat(runner.exitCode).isEqualTo(0)
    }

    @Test
    @DisplayName("결과가 RUNNING 으로 남으면 종료 코드 1 이다")
    fun exit_code_one_when_run_is_left_running() {
        val runner = SyncModeRunner({ syncRun(SyncStatus.RUNNING) }, "SCHEDULED")

        runner.run(DefaultApplicationArguments())

        assertThat(runner.exitCode).isEqualTo(1)
    }

    @Test
    @DisplayName("SYNC_TRIGGER 가 없으면 SCHEDULED 로 동기화한다")
    fun missing_trigger_env_syncs_as_scheduled() {
        val received = mutableListOf<SyncTrigger>()
        val runner = SyncModeRunner({ trigger -> syncRun(SyncStatus.SUCCEEDED).also { received += trigger } }, null)

        runner.run(DefaultApplicationArguments())

        assertThat(received).containsExactly(SyncTrigger.SCHEDULED)
    }

    @Test
    @DisplayName("SYNC_TRIGGER 가 MANUAL 이면 MANUAL 로 동기화한다")
    fun manual_trigger_env_syncs_as_manual() {
        val received = mutableListOf<SyncTrigger>()
        val runner = SyncModeRunner({ trigger -> syncRun(SyncStatus.SUCCEEDED).also { received += trigger } }, "MANUAL")

        runner.run(DefaultApplicationArguments())

        assertThat(received).containsExactly(SyncTrigger.MANUAL)
    }

    @Test
    @DisplayName("SYNC_TRIGGER 가 SCHEDULED·MANUAL 이 아니면 그 값을 담은 오류로 러너를 만들지 못한다")
    fun unknown_trigger_env_fails_with_value_in_message() {
        val thrown = catchThrowable { SyncModeRunner({ syncRun(SyncStatus.SUCCEEDED) }, "manual") }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("SYNC_TRIGGER 는 SCHEDULED 또는 MANUAL: manual")
    }

    @Test
    @DisplayName("동기화가 예상하지 못한 오류를 던지면 러너도 같은 예외를 던져 기동이 실패한다")
    fun unexpected_sync_error_propagates_from_runner() {
        val unexpected = IllegalStateException("테스트용 예상 밖 오류")
        val runner = SyncModeRunner({ throw unexpected }, "SCHEDULED")

        val thrown = catchThrowable { runner.run(DefaultApplicationArguments()) }

        assertThat(thrown).isSameAs(unexpected)
    }

    @Test
    @DisplayName("sync 프로필 컨텍스트는 웹 서버와 보안 필터 없이 뜨고 SYNC_TRIGGER 로 받은 계기로 동기화를 한 번 돌린다")
    fun sync_profile_context_starts_without_web_and_runs_one_sync_with_given_trigger() {
        val context = SpringApplication(DashboardApplication::class.java)
            .run(*syncModeArgs(FakeSyncConfiguration::class.java, "MANUAL"))
        val runs = context.getBean(SyncRunRepository::class.java).findAll()
        val securityFilterChains = context.getBeanNamesForType(SecurityFilterChain::class.java)
        context.close()

        assertThat(runs).singleElement()
            .returns(SyncTrigger.MANUAL) { it.triggerType }
            .returns(SyncStatus.SUCCEEDED) { it.status }
        assertThat(context).isNotInstanceOf(WebApplicationContext::class.java)
        assertThat(securityFilterChains).isEmpty()
    }

    @Test
    @DisplayName("sync 프로필로 실행해 동기화가 SUCCEEDED 면 runDashboard 는 종료 코드 0 을 돌려준다")
    fun run_dashboard_returns_zero_when_sync_succeeds() {
        val exitCode = runDashboard(syncModeArgs(FakeSyncConfiguration::class.java, "SCHEDULED"))

        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    @DisplayName("sync 프로필로 실행해 동기화가 FAILED 면 runDashboard 는 종료 코드 1 을 돌려준다")
    fun run_dashboard_returns_one_when_sync_fails() {
        val exitCode = runDashboard(syncModeArgs(NoBrokerageTossConfiguration::class.java, "SCHEDULED"))

        assertThat(exitCode).isEqualTo(1)
    }

    private fun failedRun() = syncRun(SyncStatus.FAILED)

    private fun syncRun(status: SyncStatus) =
        SyncRun(SyncTrigger.SCHEDULED, status, OffsetDateTime.parse("2026-11-22T03:00+09:00"))

    private fun syncModeArgs(tossConfiguration: Class<*>, trigger: String) = arrayOf(
        "--spring.profiles.active=sync",
        "--spring.flyway.enabled=true",
        "--SYNC_TRIGGER=$trigger",
        "--spring.main.sources=${TestcontainersConfiguration::class.java.name},${tossConfiguration.name}",
    )

    @TestConfiguration(proxyBeanMethods = false)
    class NoBrokerageTossConfiguration {

        @Bean
        @Primary
        fun noBrokerageTossApi() = FakeTossApi().apply { givenAccounts(emptyList()) }
    }
}
