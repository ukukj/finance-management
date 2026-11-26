package com.investment.dashboard.sync

import com.investment.dashboard.TestcontainersConfiguration
import com.investment.dashboard.account.Account
import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.account.Broker
import com.investment.dashboard.dividend.DividendProfileRepository
import com.investment.dashboard.dividend.ProfileSource
import com.investment.dashboard.fx.FxRateRepository
import com.investment.dashboard.holding.HoldingRepository
import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.ledger.LedgerEntryType
import com.investment.dashboard.ledger.LedgerSource
import com.investment.dashboard.support.MutableClock
import com.investment.dashboard.support.TestClockConfiguration
import com.investment.dashboard.toss.TossAccount
import com.investment.dashboard.toss.TossApiException
import com.investment.dashboard.toss.TossHolding
import com.investment.dashboard.toss.TossIpNotAllowedException
import com.investment.dashboard.toss.TossMalformedResponseException
import com.investment.dashboard.toss.TossTransaction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
class TossSyncServiceTest {

    @Autowired
    lateinit var syncService: TossSyncService

    @Autowired
    lateinit var syncLock: SyncLock

    @Autowired
    lateinit var fake: FakeTossApi

    @Autowired
    lateinit var ipLookup: FakePublicIpLookup

    @Autowired
    lateinit var notifier: RecordingSyncFailureNotifier

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var accountRepository: AccountRepository

    @Autowired
    lateinit var holdingRepository: HoldingRepository

    @Autowired
    lateinit var ledgerRepository: LedgerEntryRepository

    @Autowired
    lateinit var fxRateRepository: FxRateRepository

    @Autowired
    lateinit var syncRunRepository: SyncRunRepository

    @Autowired
    lateinit var profileRepository: DividendProfileRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun reset() {
        profileRepository.deleteAllInBatch()
        ledgerRepository.deleteAllInBatch()
        holdingRepository.deleteAllInBatch()
        accountRepository.deleteAllInBatch()
        fxRateRepository.deleteAllInBatch()
        syncRunRepository.deleteAllInBatch()
        fake.reset()
        ipLookup.ip = null
        notifier.notified.clear()
        clock.set(OffsetDateTime.parse("2026-11-22T10:00+09:00").toInstant())
    }

    @Test
    @DisplayName("동기화하면 토스 보유종목과 거래내역이 저장되고 기록이 SUCCEEDED 다")
    fun sync_saves_holdings_and_ledger_and_succeeds() {
        fake.givenHoldings(twoHoldings)
        fake.givenTransactions(threeRows)
        fake.givenUsdKrwRate(BigDecimal("1380.5"))

        val run = syncService.run(SyncTrigger.MANUAL)

        assertThat(run.status).isEqualTo(SyncStatus.SUCCEEDED)
        assertThat(run.finishedAt).isAtSameInstantAs(OffsetDateTime.parse("2026-11-22T10:00+09:00"))
        assertThat(holdingRepository.count()).isEqualTo(2L)
        assertThat(ledgerRepository.count()).isEqualTo(3L)
        assertThat(fxRateRepository.findByIdOrNull("USD")?.krwRate).isEqualByComparingTo("1380.5")
        assertThat(accountRepository.findByTossAccountSeq(1L)?.name).isEqualTo("토스 일반")
    }

    @Test
    @DisplayName("두 번 연속 동기화해도 원장 행 수가 같다")
    fun syncing_twice_keeps_ledger_row_count() {
        fake.givenHoldings(twoHoldings)
        fake.givenTransactions(threeRows)
        syncService.run(SyncTrigger.MANUAL)

        val run = syncService.run(SyncTrigger.MANUAL)

        assertThat(run.status).isEqualTo(SyncStatus.SUCCEEDED)
        assertThat(ledgerRepository.count()).isEqualTo(3L)
    }

    @Test
    @DisplayName("토스에서 사라진 거래는 다음 동기화 뒤 원장에서도 사라진다")
    fun transaction_gone_from_toss_is_removed_from_ledger() {
        fake.givenTransactions(threeRows)
        syncService.run(SyncTrigger.MANUAL)
        fake.givenTransactions(listOf(buyGanaElectronics, buyTestEtf))

        syncService.run(SyncTrigger.MANUAL)

        assertThat(ledgerRepository.count()).isEqualTo(2L)
    }

    @Test
    @DisplayName("수동 입력 원장 행은 동기화가 지우지 않는다")
    fun sync_keeps_manual_ledger_rows() {
        val miraeId = accountRepository.save(Account(AccountKind.ISA, Broker.MIRAE, "미래에셋 ISA")).id!!
        ledgerRepository.save(manualDeposit(miraeId))
        fake.givenTransactions(threeRows)

        syncService.run(SyncTrigger.MANUAL)

        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(miraeId)).hasSize(1)
    }

    @Test
    @DisplayName("거래내역 조회 중 실패하면 이전 데이터가 그대로 남고 기록은 FAILED 다")
    fun failure_keeps_previous_snapshot() {
        fake.givenTransactions(threeRows)
        syncService.run(SyncTrigger.MANUAL)
        fake.failOnTransactions(TossApiException(500, "internal", "boom"))

        val run = syncService.run(SyncTrigger.MANUAL)

        assertThat(run.status).isEqualTo(SyncStatus.FAILED)
        assertThat(run.failureReason).isEqualTo("동기화 실패: boom")
        assertThat(ledgerRepository.count()).isEqualTo(3L)
    }

    @Test
    @DisplayName("403 이면 현재 공인 IP 를 사유에 담아 FAILED 로 기록하고 알림을 한 번 보낸다")
    fun ip_not_allowed_records_current_ip_and_notifies_once() {
        ipLookup.ip = "203.0.113.7"
        fake.failOnAccounts(TossIpNotAllowedException(403, "ip-not-allowed", "허용되지 않은 IP"))

        val run = syncService.run(SyncTrigger.MANUAL)

        assertThat(run.status).isEqualTo(SyncStatus.FAILED)
        assertThat(run.failureReason).isEqualTo("허용 IP 확인 필요 (현재 IP 203.0.113.7)")
        assertThat(notifier.notified).singleElement().extracting { it.id }.isEqualTo(run.id)
    }

    @Test
    @DisplayName("공인 IP 조회도 실패하면 사유에 확인 실패를 적는다")
    fun ip_lookup_failure_is_written_in_reason() {
        fake.failOnAccounts(TossIpNotAllowedException(403, "ip-not-allowed", "허용되지 않은 IP"))

        val run = syncService.run(SyncTrigger.MANUAL)

        assertThat(run.failureReason).isEqualTo("허용 IP 확인 필요 (현재 IP 확인 실패)")
    }

    @Test
    @DisplayName("잠금이 이미 잡혀 있으면 토스를 부르지 않고 SKIPPED 다")
    fun skips_without_calling_toss_when_lock_is_held() {
        val held = checkNotNull(syncLock.tryAcquire())

        val run = held.use { syncService.run(SyncTrigger.MANUAL) }

        assertThat(run.status).isEqualTo(SyncStatus.SKIPPED)
        assertThat(fake.calls).isZero()
    }

    @Test
    @DisplayName("종목이 붙은 입금은 원장에 DIVIDEND 로 저장되고 raw 에는 토스 원래 유형이 남는다")
    fun stock_deposit_is_saved_as_dividend_with_original_type_in_raw() {
        fake.givenTransactions(
            listOf(
                tossTransaction("DEPOSIT", symbol = "0000A0", assetClass = "STOCK", amount = "15750"),
                tossTransaction("DEPOSIT", amount = "3000000"),
            ),
        )

        syncService.run(SyncTrigger.MANUAL)

        val entries = ledgerRepository.findAll()
        assertThat(entries).extracting<LedgerEntryType> { it.type }
            .containsExactlyInAnyOrder(LedgerEntryType.DIVIDEND, LedgerEntryType.DEPOSIT)
        val dividend = entries.single { it.type == LedgerEntryType.DIVIDEND }
        assertThat(dividend.symbol).isEqualTo("0000A0")
        assertThat(JsonMapper.shared().readTree(dividend.raw).get("transactionType").stringValue())
            .isEqualTo("DEPOSIT")
    }

    @Test
    @DisplayName("증권 입고는 금액 없이 저장된다")
    fun securities_transfer_in_is_saved_without_amount() {
        fake.givenTransactions(
            listOf(tossTransaction("SECURITIES_TRANSFER_IN", symbol = "900110", assetClass = "STOCK", quantity = "5")),
        )

        syncService.run(SyncTrigger.MANUAL)

        val entry = ledgerRepository.findAll().single()
        assertThat(entry.type).isEqualTo(LedgerEntryType.SECURITIES_TRANSFER_IN)
        assertThat(entry.amount).isNull()
        assertThat(entry.netAmount).isNull()
        assertThat(entry.quantity).isEqualByComparingTo("5")
    }

    @Test
    @DisplayName("모르는 거래 유형이 오면 FAILED 이고 이전 보유종목과 원장이 그대로 남는다")
    fun unknown_transaction_type_fails_and_keeps_previous_snapshot() {
        fake.givenHoldings(twoHoldings)
        fake.givenTransactions(threeRows)
        syncService.run(SyncTrigger.MANUAL)
        fake.givenHoldings(listOf(ganaElectronics))
        fake.givenTransactions(listOf(buyGanaElectronics, tossTransaction("STOCK_SPLIT", symbol = "900110")))

        val run = syncService.run(SyncTrigger.MANUAL)

        assertThat(run.status).isEqualTo(SyncStatus.FAILED)
        assertThat(run.failureReason).isEqualTo("동기화 실패: 알 수 없는 거래 유형 STOCK_SPLIT")
        assertThat(holdingRepository.count()).isEqualTo(2L)
        assertThat(ledgerRepository.count()).isEqualTo(3L)
    }

    @Test
    @DisplayName("토스에 종합매매 계좌가 없으면 접두사 없는 사유로 FAILED 다")
    fun fails_when_toss_has_no_brokerage_account() {
        fake.givenAccounts(listOf(TossAccount("00000000000", 1L, "PENSION")))

        val run = syncService.run(SyncTrigger.MANUAL)

        assertThat(run.status).isEqualTo(SyncStatus.FAILED)
        assertThat(run.failureReason).isEqualTo("토스 종합매매 계좌 없음")
    }

    @Test
    @DisplayName("토스 응답 형식 오류도 메시지를 사유에 담아 FAILED 로 끝난다")
    fun malformed_toss_response_is_recorded_as_failed() {
        fake.failOnTransactions(TossMalformedResponseException("토스 응답에 nextCursor 값 없음"))

        val run = syncService.run(SyncTrigger.MANUAL)

        assertThat(run.status).isEqualTo(SyncStatus.FAILED)
        assertThat(run.failureReason).isEqualTo("동기화 실패: 토스 응답에 nextCursor 값 없음")
    }

    @Test
    @DisplayName("실패 사유가 500자를 넘으면 500자로 잘라 FAILED 로 저장한다")
    fun long_failure_reason_is_cut_to_500_chars() {
        fake.failOnTransactions(TossApiException(500, "internal", "가".repeat(600)))

        val run = syncService.run(SyncTrigger.MANUAL)

        val saved = syncRunRepository.findByIdOrNull(run.id!!)
        assertThat(saved?.status).isEqualTo(SyncStatus.FAILED)
        assertThat(saved?.failureReason).hasSize(500).startsWith("동기화 실패: 가가")
    }

    @Test
    @DisplayName("토스를 부르는 동안에는 DB 트랜잭션이 열려 있지 않다")
    fun toss_is_called_outside_db_transaction() {
        val transactionActive = mutableListOf<Boolean>()
        fake.onEachCall { transactionActive += TransactionSynchronizationManager.isActualTransactionActive() }

        syncService.run(SyncTrigger.MANUAL)

        assertThat(transactionActive).containsExactly(false, false, false, false)
    }

    @Test
    @DisplayName("동기화가 실패해도 advisory lock 을 남기지 않는다")
    fun releases_advisory_lock_even_when_sync_fails() {
        fake.failOnTransactions(TossApiException(500, "internal", "boom"))

        syncService.run(SyncTrigger.MANUAL)

        assertThat(jdbc.queryForObject(SYNC_ADVISORY_LOCK_COUNT_SQL, Int::class.java)).isZero()
    }

    @Test
    @DisplayName("토스 계좌의 수동 입력 원장 행은 다음 동기화 뒤에도 남는다")
    fun sync_keeps_manual_ledger_rows_on_toss_account() {
        fake.givenTransactions(threeRows)
        syncService.run(SyncTrigger.MANUAL)
        val tossAccountId = accountRepository.findByTossAccountSeq(1L)!!.id!!
        ledgerRepository.save(manualDeposit(tossAccountId))

        syncService.run(SyncTrigger.MANUAL)

        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(tossAccountId))
            .extracting<LedgerSource> { it.source }
            .containsExactlyInAnyOrder(LedgerSource.TOSS, LedgerSource.TOSS, LedgerSource.TOSS, LedgerSource.MANUAL)
    }

    @Test
    @DisplayName("토스를 부를 때는 RUNNING 기록이 이미 커밋돼 있다")
    fun running_record_is_committed_before_toss_is_called() {
        val statusesSeenByOtherConnection = mutableListOf<String?>()
        fake.onEachCall { statusesSeenByOtherConnection += jdbc.queryForList(SYNC_RUN_STATUS_SQL, String::class.java) }

        syncService.run(SyncTrigger.MANUAL)

        assertThat(statusesSeenByOtherConnection).containsExactly("RUNNING", "RUNNING", "RUNNING", "RUNNING")
    }

    @Test
    @DisplayName("메시지 없는 DB 오류는 예외 클래스 이름을 사유에 적는다")
    fun db_failure_without_message_records_class_name() {
        fake.failOnTransactions(QueryTimeoutException(null))

        val run = syncService.run(SyncTrigger.MANUAL)

        assertThat(run.failureReason).isEqualTo("동기화 실패: QueryTimeoutException")
    }

    @Test
    @DisplayName("트랜잭션 오류는 메시지를 사유에 담아 FAILED 로 끝난다")
    fun transaction_failure_is_recorded_as_failed() {
        fake.failOnTransactions(TransactionSystemException("커밋 실패"))

        val run = syncService.run(SyncTrigger.MANUAL)

        assertThat(run.status).isEqualTo(SyncStatus.FAILED)
        assertThat(run.failureReason).isEqualTo("동기화 실패: 커밋 실패")
    }

    @Test
    @DisplayName("스냅샷 저장 중 DB 오류가 나면 예외 없이 FAILED 이고 이전 보유종목과 원장이 그대로 남는다")
    fun db_failure_during_write_fails_and_keeps_previous_snapshot() {
        fake.givenHoldings(twoHoldings)
        fake.givenTransactions(threeRows)
        syncService.run(SyncTrigger.MANUAL)
        fake.givenHoldings(listOf(holdingWithTooLongSymbol))
        fake.givenTransactions(listOf(buyGanaElectronics))

        val run = syncService.run(SyncTrigger.MANUAL)

        assertThat(run.status).isEqualTo(SyncStatus.FAILED)
        assertThat(run.failureReason)
            .startsWith("동기화 실패: ")
            .contains("value too long for type character varying(20)")
        assertThat(holdingRepository.count()).isEqualTo(2L)
        assertThat(ledgerRepository.count()).isEqualTo(3L)
    }

    @Test
    @DisplayName("예상하지 못한 오류는 FAILED 로 기록하고 알림과 잠금 해제 뒤 같은 예외를 다시 던진다")
    fun unexpected_error_is_recorded_notified_and_rethrown() {
        val unexpected = UnexpectedSyncTestFailure()
        fake.failOnTransactions(unexpected)

        val thrown = catchThrowable { syncService.run(SyncTrigger.MANUAL) }

        assertThat(thrown).isSameAs(unexpected)
        val recorded = syncRunRepository.findAll().single()
        assertThat(recorded.status).isEqualTo(SyncStatus.FAILED)
        assertThat(recorded.failureReason).isEqualTo("동기화 실패: 예상하지 못한 오류")
        assertThat(notifier.notified).singleElement().extracting { it.id }.isEqualTo(recorded.id)
        assertThat(jdbc.queryForObject(SYNC_ADVISORY_LOCK_COUNT_SQL, Int::class.java)).isZero()
    }

    @Test
    @DisplayName("동기화 뒤 배당 이력이 있는 보유 종목에 AUTO 배당 정보가 생긴다")
    fun sync_creates_auto_profile_for_held_symbol_with_dividend_history() {
        clock.set(OffsetDateTime.parse("2025-06-15T10:00+09:00").toInstant())
        fake.givenHoldings(twoHoldings)
        fake.givenTransactions(threeRows)

        syncService.run(SyncTrigger.MANUAL)

        val tossAccountId = accountRepository.findByTossAccountSeq(1L)!!.id!!
        val profile = profileRepository.findByAccountIdAndSymbol(tossAccountId, "900110")
        assertThat(profile?.source).isEqualTo(ProfileSource.AUTO)
        assertThat(profile?.perShare).isEqualByComparingTo("300")
        assertThat(profile?.payMonthSet()).containsExactly(5)
    }

    @Test
    @DisplayName("보유 종목 매수 행에 수량이 없으면 FAILED 로 기록하고 예외를 다시 던지며 이전 보유종목·원장·배당 정보가 그대로 남는다")
    fun trade_without_quantity_fails_and_keeps_previous_snapshot_and_profiles() {
        clock.set(OffsetDateTime.parse("2025-06-15T10:00+09:00").toInstant())
        fake.givenHoldings(twoHoldings)
        fake.givenTransactions(threeRows)
        syncService.run(SyncTrigger.MANUAL)
        fake.givenHoldings(listOf(ganaElectronics))
        fake.givenTransactions(listOf(buyGanaElectronicsWithoutQuantity, ganaElectronicsDividend))

        val thrown = catchThrowable { syncService.run(SyncTrigger.MANUAL) }

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
            .hasMessage("수량 없는 BUY 행: 900110 2025-05-30")
        assertThat(syncRunRepository.findAll().filter { it.status == SyncStatus.FAILED })
            .singleElement().extracting { it.failureReason }.isEqualTo("동기화 실패: 예상하지 못한 오류")
        assertThat(holdingRepository.count()).isEqualTo(2L)
        assertThat(ledgerRepository.count()).isEqualTo(3L)
        val tossAccountId = accountRepository.findByTossAccountSeq(1L)!!.id!!
        val profile = profileRepository.findByAccountIdAndSymbol(tossAccountId, "900110")
        assertThat(profile?.source).isEqualTo(ProfileSource.AUTO)
        assertThat(profile?.perShare).isEqualByComparingTo("300")
    }

    private fun manualDeposit(accountId: Long) =
        LedgerEntry(accountId, LedgerEntryType.DEPOSIT, "KRW", LocalDate.parse("2026-03-02"), LedgerSource.MANUAL)
            .apply {
                amount = BigDecimal("1000000")
                netAmount = BigDecimal("1000000")
            }

    @TestConfiguration(proxyBeanMethods = false)
    class FakeTossConfiguration {

        @Bean
        @Primary
        fun fakeTossApi() = FakeTossApi()

        @Bean
        @Primary
        fun fakePublicIpLookup() = FakePublicIpLookup()

        @Bean
        @Primary
        fun recordingSyncFailureNotifier() = RecordingSyncFailureNotifier()
    }
}

private const val SYNC_ADVISORY_LOCK_COUNT_SQL =
    "select count(*) from pg_locks where locktype = 'advisory' and classid = 0 and objid = 727001"
private const val SYNC_RUN_STATUS_SQL = "select status from sync_run"

private val ganaElectronics = TossHolding("900110", "가나전자", "KR", "KRW", BigDecimal("12"), BigDecimal("45200"))
private val testEtf = TossHolding("0000A0", "마바 테스트 ETF", "KR", "KRW", BigDecimal("150"), BigDecimal("10450.3"))
private val twoHoldings = listOf(ganaElectronics, testEtf)
private val holdingWithTooLongSymbol =
    TossHolding("ABCDEFGHIJKLMNOPQRSTU", "긴 코드 종목", "US", "USD", BigDecimal("1"), null)

private val buyGanaElectronics =
    tossTransaction("BUY", symbol = "900110", assetClass = "STOCK", quantity = "15", amount = "678000")
private val buyTestEtf =
    tossTransaction("BUY", symbol = "0000A0", assetClass = "STOCK", quantity = "40", amount = "415200")
private val buyGanaElectronicsWithoutQuantity =
    tossTransaction("BUY", symbol = "900110", assetClass = "STOCK", amount = "678000")
private val ganaElectronicsDividend =
    tossTransaction("DIVIDEND", symbol = "900110", assetClass = "STOCK", amount = "4500")
private val threeRows = listOf(buyGanaElectronics, buyTestEtf, ganaElectronicsDividend)

private fun tossTransaction(
    type: String,
    symbol: String? = null,
    assetClass: String? = null,
    quantity: String? = null,
    amount: String? = null,
): TossTransaction {
    val raw = mapOf(
        "transactionType" to type,
        "marketCountry" to "KR",
        "currency" to "KRW",
        "symbol" to symbol,
        "assetClass" to assetClass,
        "quantity" to quantity,
        "amount" to amount,
        "commission" to "0",
        "tax" to "0",
        "netAmount" to amount,
        "settlementDate" to "2025-05-30",
        "settled" to true,
        "isCancellation" to false,
    )
    return TossTransaction(
        transactionType = type,
        marketCountry = "KR",
        currency = "KRW",
        symbol = symbol,
        assetClass = assetClass,
        isinCode = null,
        quantity = quantity?.let(::BigDecimal),
        filledPrice = null,
        amount = amount?.let(::BigDecimal),
        commission = BigDecimal.ZERO,
        tax = BigDecimal.ZERO,
        netAmount = amount?.let(::BigDecimal),
        exchangeRate = null,
        settlementDate = LocalDate.parse("2025-05-30"),
        settled = true,
        transactionTime = null,
        isCancellation = false,
        rawJson = JsonMapper.shared().writeValueAsString(raw),
    )
}

private class UnexpectedSyncTestFailure : RuntimeException("테스트용 예상 밖 오류")
