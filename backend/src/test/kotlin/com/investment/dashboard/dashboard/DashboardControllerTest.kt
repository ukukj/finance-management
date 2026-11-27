package com.investment.dashboard.dashboard

import com.investment.dashboard.TestcontainersConfiguration
import com.investment.dashboard.account.Account
import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.account.Broker
import com.investment.dashboard.dividend.DividendProfileRepository
import com.investment.dashboard.dividend.dividend
import com.investment.dashboard.fx.FxRateRepository
import com.investment.dashboard.holding.HoldingRepository
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.support.MutableClock
import com.investment.dashboard.support.TestClockConfiguration
import com.investment.dashboard.sync.SyncRun
import com.investment.dashboard.sync.SyncRunRepository
import com.investment.dashboard.sync.SyncStatus
import com.investment.dashboard.sync.SyncTrigger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.OffsetDateTime

@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
class DashboardControllerTest {

    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var accountRepository: AccountRepository

    @Autowired
    lateinit var holdingRepository: HoldingRepository

    @Autowired
    lateinit var ledgerRepository: LedgerEntryRepository

    @Autowired
    lateinit var profileRepository: DividendProfileRepository

    @Autowired
    lateinit var fxRateRepository: FxRateRepository

    @Autowired
    lateinit var syncRunRepository: SyncRunRepository

    private lateinit var rest: RestTestClient

    @BeforeEach
    fun reset() {
        profileRepository.deleteAllInBatch()
        ledgerRepository.deleteAllInBatch()
        holdingRepository.deleteAllInBatch()
        accountRepository.deleteAllInBatch()
        syncRunRepository.deleteAllInBatch()
        fxRateRepository.deleteAllInBatch()
        clock.set(OffsetDateTime.parse("2026-11-22T10:00+09:00").toInstant())
        val mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        rest = RestTestClient.bindTo(mvc).build()
    }

    @Test
    @DisplayName("로그인 없이 부르면 401")
    fun dashboard_without_login_is_401() {
        rest.get().uri("/api/dashboard")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().isEmpty()
    }

    @Test
    @WithMockUser
    @DisplayName("응답 JSON 에 headline.received.gross 가 숫자로 있다")
    fun dashboard_json_has_received_gross_as_number() {
        val brokerage = accountRepository.save(Account(AccountKind.BROKERAGE, Broker.TOSS, "토스 일반")).id!!
        val entry = dividend("900110", "100000", "84600", "KRW", "2026-03-10").apply { accountId = brokerage }
        ledgerRepository.save(entry)

        rest.get().uri("/api/dashboard")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.headline.received.gross").isNumber()
            .jsonPath("$.headline.received.gross").isEqualTo(100000)
    }

    @Test
    @WithMockUser
    @DisplayName("응답 JSON 의 sync 에 마지막 실행 시작 시각이 +09:00 시각으로 있다")
    fun dashboard_json_has_sync_last_started_at_in_seoul_offset() {
        syncRunRepository.save(
            SyncRun(SyncTrigger.MANUAL, SyncStatus.RUNNING, OffsetDateTime.parse("2026-11-22T10:00+09:00")),
        )

        rest.get().uri("/api/dashboard")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.sync.lastStartedAt").isEqualTo("2026-11-22T10:00:00+09:00")
    }
}
