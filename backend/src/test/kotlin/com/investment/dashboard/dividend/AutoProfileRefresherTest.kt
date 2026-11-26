package com.investment.dashboard.dividend

import com.investment.dashboard.TestcontainersConfiguration
import com.investment.dashboard.account.Account
import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.account.Broker
import com.investment.dashboard.holding.Holding
import com.investment.dashboard.holding.HoldingRepository
import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.support.MutableClock
import com.investment.dashboard.support.TestClockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
class AutoProfileRefresherTest {

    @Autowired
    lateinit var refresher: AutoProfileRefresher

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

    private var tossAccountId = 0L

    @BeforeEach
    fun reset() {
        profileRepository.deleteAllInBatch()
        ledgerRepository.deleteAllInBatch()
        holdingRepository.deleteAllInBatch()
        accountRepository.deleteAllInBatch()
        clock.set(OffsetDateTime.parse("2026-11-22T10:00+09:00").toInstant())
        tossAccountId = accountRepository.save(Account(AccountKind.BROKERAGE, Broker.TOSS, "토스 일반")).id!!
    }

    @Test
    @DisplayName("수동 입력 뒤 보유 중 받은 배당이 들어오면 그 회차로 자동 추정해 바뀐다")
    fun manual_profile_is_replaced_by_later_dividend_received_while_held() {
        holdingRepository.save(holding("SCHD"))
        saveLedger(
            buy("SCHD", "100", "2025-12-01"),
            dividend("SCHD", "30.0000", "25.5000", "USD", "2026-06-15"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2026-10-15"),
        )
        profileRepository.save(profile("SCHD", ProfileSource.MANUAL, "2026-10-01T09:00+09:00"))

        refresher.refresh(tossAccountId, LocalDate.parse("2026-11-22"))

        val saved = profileRepository.findByAccountIdAndSymbol(tossAccountId, "SCHD")
        assertThat(saved?.source).isEqualTo(ProfileSource.AUTO)
        assertThat(saved?.perShare).isEqualByComparingTo("0.360000")
        assertThat(saved?.payMonthSet()).containsExactly(6, 10)
        assertThat(saved?.netRatio).isEqualByComparingTo("0.850000")
        assertThat(saved?.updatedAt).isAtSameInstantAs(OffsetDateTime.parse("2026-11-22T10:00+09:00"))
    }

    @Test
    @DisplayName("수동 입력이 최근 배당 뒤면 동기화가 덮지 않는다")
    fun manual_profile_entered_after_latest_dividend_is_kept() {
        holdingRepository.save(holding("SCHD"))
        saveLedger(
            buy("SCHD", "100", "2025-12-01"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2026-09-15"),
        )
        profileRepository.save(profile("SCHD", ProfileSource.MANUAL, "2026-10-01T09:00+09:00"))

        refresher.refresh(tossAccountId, LocalDate.parse("2026-11-22"))

        val saved = profileRepository.findByAccountIdAndSymbol(tossAccountId, "SCHD")
        assertThat(saved?.source).isEqualTo(ProfileSource.MANUAL)
        assertThat(saved?.perShare).isEqualByComparingTo("0.5")
    }

    @Test
    @DisplayName("수동 입력의 한국 날짜가 주당 배당을 낸 결제일과 같으면 덮지 않는다")
    fun manual_profile_on_per_share_basis_date_in_kst_is_kept() {
        holdingRepository.save(holding("SCHD"))
        saveLedger(
            buy("SCHD", "100", "2025-12-01"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2026-10-15"),
        )
        profileRepository.save(profile("SCHD", ProfileSource.MANUAL, "2026-10-14T16:00Z"))

        refresher.refresh(tossAccountId, LocalDate.parse("2026-11-22"))

        val saved = profileRepository.findByAccountIdAndSymbol(tossAccountId, "SCHD")
        assertThat(saved?.source).isEqualTo(ProfileSource.MANUAL)
        assertThat(saved?.perShare).isEqualByComparingTo("0.5")
    }

    @Test
    @DisplayName("수동 입력 뒤 들어온 배당이 결제일 수량 0 회차면 주당 배당 근거가 아니라 덮지 않는다")
    fun manual_profile_is_kept_when_later_dividend_was_paid_with_zero_quantity() {
        holdingRepository.save(holding("SCHD"))
        saveLedger(
            buy("SCHD", "100", "2026-01-02"),
            dividend("SCHD", "36.0000", "30.6000", "USD", "2026-06-15"),
            sell("SCHD", "100", "2026-09-01"),
            dividend("SCHD", "40.0000", "34.0000", "USD", "2026-10-15"),
            buy("SCHD", "100", "2026-10-20"),
        )
        profileRepository.save(profile("SCHD", ProfileSource.MANUAL, "2026-10-01T09:00+09:00"))

        refresher.refresh(tossAccountId, LocalDate.parse("2026-11-22"))

        val saved = profileRepository.findByAccountIdAndSymbol(tossAccountId, "SCHD")
        assertThat(saved?.source).isEqualTo(ProfileSource.MANUAL)
        assertThat(saved?.perShare).isEqualByComparingTo("0.5")
    }

    @Test
    @DisplayName("이력으로 추정할 수 없으면 AUTO 배당 정보를 지운다")
    fun auto_profile_is_deleted_without_estimate() {
        holdingRepository.save(holding("SCHD"))
        profileRepository.save(profile("SCHD", ProfileSource.AUTO))

        refresher.refresh(tossAccountId, LocalDate.parse("2026-11-22"))

        assertThat(profileRepository.findByAccountIdAndSymbol(tossAccountId, "SCHD")).isNull()
    }

    @Test
    @DisplayName("이력으로 추정할 수 없으면 MANUAL 배당 정보는 그대로 둔다")
    fun manual_profile_is_kept_without_estimate() {
        holdingRepository.save(holding("SCHD"))
        profileRepository.save(profile("SCHD", ProfileSource.MANUAL))

        refresher.refresh(tossAccountId, LocalDate.parse("2026-11-22"))

        val saved = profileRepository.findByAccountIdAndSymbol(tossAccountId, "SCHD")
        assertThat(saved?.source).isEqualTo(ProfileSource.MANUAL)
        assertThat(saved?.perShare).isEqualByComparingTo("0.5")
    }

    @Test
    @DisplayName("보유하지 않는 종목의 AUTO 배당 정보는 건드리지 않는다")
    fun profile_of_symbol_not_held_is_untouched() {
        profileRepository.save(profile("VTI", ProfileSource.AUTO))

        refresher.refresh(tossAccountId, LocalDate.parse("2026-11-22"))

        val kept = profileRepository.findByAccountIdAndSymbol(tossAccountId, "VTI")
        assertThat(kept?.source).isEqualTo(ProfileSource.AUTO)
    }

    private fun holding(symbol: String) =
        Holding(tossAccountId, symbol, "테스트 배당 ETF", "US", "USD", BigDecimal("100")).apply {
            updatedAt = OffsetDateTime.parse("2026-11-22T10:00+09:00")
        }

    private fun profile(symbol: String, source: ProfileSource, updatedAt: String = "2026-01-01T09:00+09:00") =
        DividendProfile(tossAccountId, symbol, BigDecimal("0.5"), "USD", "1", BigDecimal.ONE).apply {
            this.source = source
            this.updatedAt = OffsetDateTime.parse(updatedAt)
        }

    private fun saveLedger(vararg entries: LedgerEntry) {
        ledgerRepository.saveAll(entries.map { it.apply { accountId = tossAccountId } })
    }
}
