package com.investment.dashboard.dashboard

import com.investment.dashboard.TestcontainersConfiguration
import com.investment.dashboard.account.Account
import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.account.Broker
import com.investment.dashboard.dividend.DividendProfile
import com.investment.dashboard.dividend.DividendProfileRepository
import com.investment.dashboard.dividend.ProfileSource
import com.investment.dashboard.dividend.dividend
import com.investment.dashboard.fx.FxRate
import com.investment.dashboard.fx.FxRateRepository
import com.investment.dashboard.holding.Holding
import com.investment.dashboard.holding.HoldingRepository
import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.ledger.LedgerEntryType
import com.investment.dashboard.ledger.LedgerSource
import com.investment.dashboard.support.MutableClock
import com.investment.dashboard.support.TestClockConfiguration
import com.investment.dashboard.sync.SyncRun
import com.investment.dashboard.sync.SyncRunRepository
import com.investment.dashboard.sync.SyncStatus
import com.investment.dashboard.sync.SyncTrigger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
class DashboardServiceTest {

    @Autowired
    lateinit var dashboardService: DashboardService

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

    private var brokerage = 0L
    private var isa = 0L
    private var pension = 0L

    @BeforeEach
    fun reset() {
        profileRepository.deleteAllInBatch()
        ledgerRepository.deleteAllInBatch()
        holdingRepository.deleteAllInBatch()
        accountRepository.deleteAllInBatch()
        syncRunRepository.deleteAllInBatch()
        fxRateRepository.deleteAllInBatch()
        clock.set(OffsetDateTime.parse("2026-11-22T10:00+09:00").toInstant())
        brokerage = accountRepository.save(Account(AccountKind.BROKERAGE, Broker.TOSS, "토스 일반")).id!!
        isa = accountRepository.save(Account(AccountKind.ISA, Broker.MIRAE, "미래에셋 ISA")).id!!
        pension = accountRepository.save(Account(AccountKind.PENSION_SAVINGS, Broker.MIRAE, "미래에셋 연금저축")).id!!
    }

    @Test
    @DisplayName("올해 받은 배당을 쓸 수 있는 돈과 묶인 돈으로 나눠 합친다")
    fun received_split_by_account_kind() {
        dividendIn(brokerage, "KRW", "100000", "84600", "2026-03-10")
        dividendIn(isa, "KRW", "50000", "50000", "2026-05-02")
        dividendIn(brokerage, "KRW", "70000", "59220", "2025-12-10")

        val headline = dashboardService.build().headline

        assertThat(headline.received).isEqualTo(KrwPair(150000, 134600))
        assertThat(headline.receivedSpendable).isEqualTo(KrwPair(100000, 84600))
        assertThat(headline.receivedLocked).isEqualTo(KrwPair(50000, 50000))
    }

    @Test
    @DisplayName("연금저축 배당은 묶인 돈에 들어가고 쓸 수 있는 돈에는 들어가지 않는다")
    fun pension_dividend_is_locked() {
        dividendIn(pension, "KRW", "7000", "7000", "2026-04-10")

        val headline = dashboardService.build().headline

        assertThat(headline.receivedLocked).isEqualTo(KrwPair(7000, 7000))
        assertThat(headline.receivedSpendable).isEqualTo(KrwPair(0, 0))
    }

    @Test
    @DisplayName("이자는 받은 배당에 넣지 않는다")
    fun interest_is_not_received_dividend() {
        dividendIn(brokerage, "KRW", "10000", "8460", "2026-06-10")
        interestIn(brokerage, "1200", "1015", "2026-06-30")

        val headline = dashboardService.build().headline

        assertThat(headline.received).isEqualTo(KrwPair(10000, 8460))
    }

    @Test
    @DisplayName("취소된 배당은 받은 배당에 넣지 않는다")
    fun cancelled_dividend_is_not_received() {
        dividendIn(brokerage, "KRW", "10000", "8460", "2026-04-10")
        cancelledDividendIn(brokerage, "3000", "2538", "2026-04-15")

        val headline = dashboardService.build().headline

        assertThat(headline.received).isEqualTo(KrwPair(10000, 8460))
    }

    @Test
    @DisplayName("결제일이 오늘인 배당은 받은 배당에 넣고 내일인 배당은 넣지 않는다")
    fun dividend_settled_after_today_is_not_received() {
        dividendIn(brokerage, "KRW", "1000", "846", "2026-11-22")
        dividendIn(brokerage, "KRW", "2000", "1692", "2026-11-23")

        val headline = dashboardService.build().headline

        assertThat(headline.received).isEqualTo(KrwPair(1000, 846))
    }

    @Test
    @DisplayName("세후 금액이 없는 배당 행은 받은 배당에 넣지 않는다")
    fun dividend_without_net_amount_is_not_received() {
        dividendIn(brokerage, "KRW", "10000", "8460", "2026-04-10")
        ledgerRepository.save(
            dividend("900110", "3000", "2538", "KRW", "2026-04-15").apply {
                accountId = brokerage
                netAmount = null
            },
        )

        val headline = dashboardService.build().headline

        assertThat(headline.received).isEqualTo(KrwPair(10000, 8460))
    }

    @Test
    @DisplayName("세전 금액이 0 인 배당 행은 그 달 예상을 받은 것으로 만들지 않는다")
    fun zero_amount_dividend_does_not_count_as_received_payment() {
        val gana = krHolding(brokerage, "900110", "10")
        profileFor(gana, "500", "11", "1")
        krwDividendIn(brokerage, "900110", "0", "0", "2026-11-20")

        val headline = dashboardService.build().headline

        assertThat(headline.yearTotalExpected).isEqualTo(KrwPair(5000, 5000))
    }

    @Test
    @DisplayName("미국 배당은 그 행의 환율로 원화 환산한다")
    fun usd_dividend_uses_exchange_rate_of_its_row() {
        latestUsdRate("1400")
        usdDividendIn(brokerage, "36.00", "30.60", "1380.5", "2026-10-15")

        val headline = dashboardService.build().headline

        assertThat(headline.received).isEqualTo(KrwPair(49698, 42243))
    }

    @Test
    @DisplayName("미국 배당은 행마다 원 단위로 반올림한 뒤 더한다")
    fun usd_dividends_are_rounded_half_up_per_row_before_summing() {
        usdDividendIn(brokerage, "0.50", "0.50", "1381", "2026-04-15")
        usdDividendIn(brokerage, "0.50", "0.50", "1381", "2026-07-15")

        val headline = dashboardService.build().headline

        assertThat(headline.received).isEqualTo(KrwPair(1382, 1382))
    }

    @Test
    @DisplayName("환율 없는 미국 배당이 있으면 종목과 결제일을 담아 실패한다")
    fun usd_dividend_without_exchange_rate_fails_with_symbol_and_date() {
        latestUsdRate("1400")
        usdDividendIn(brokerage, "36.00", "30.60", null, "2026-10-15")

        assertThatThrownBy { dashboardService.build() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("환율 없는 USD 배당: SCHD 2026-10-15")
    }

    @Test
    @DisplayName("보유하지 않는 종목의 지난해 이전 미국 배당은 환율이 없어도 화면 숫자에 쓰이지 않아 실패하지 않는다")
    fun old_usd_dividend_without_rate_of_symbol_not_held_does_not_fail() {
        usdDividendIn(brokerage, "36.00", "30.60", null, "2024-10-15")

        val headline = dashboardService.build().headline

        assertThat(headline.received).isEqualTo(KrwPair(0, 0))
    }

    @Test
    @DisplayName("보유 종목의 예전 미국 배당에 환율이 없어도 최근 지급일 행에 환율이 있으면 실패하지 않는다")
    fun older_usd_dividend_without_rate_of_held_symbol_does_not_fail_when_latest_has_rate() {
        usHolding(brokerage, "SCHD", "120")
        usdDividendIn(brokerage, "30.00", "25.50", null, "2024-10-15")
        usdDividendIn(brokerage, "36.00", "30.60", "1400", "2025-12-15")

        val row = dashboardService.build().row(brokerage, "SCHD")

        assertThat(row.lastPaid).isEqualTo(KrwPair(50400, 42840))
    }

    @Test
    @DisplayName("원화·달러가 아닌 배당이 있으면 통화와 종목과 결제일을 담아 실패한다")
    fun dividend_in_other_currency_fails_with_currency_symbol_and_date() {
        ledgerRepository.save(dividend("VTI", "10.00", "8.50", "EUR", "2026-10-15").apply { accountId = brokerage })

        assertThatThrownBy { dashboardService.build() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("원화로 바꿀 수 없는 배당 통화: EUR VTI 2026-10-15")
    }

    @Test
    @DisplayName("다음 달 예상은 12월이 지급 월인 종목의 주당 배당 × 수량을 최신 환율로 바꾼 것이다")
    fun next_month_expected_is_per_share_times_quantity_at_latest_rate() {
        latestUsdRate("1400")
        val schd = usHolding(brokerage, "SCHD", "120")
        profileFor(schd, "0.36", "3,6,9,12", "0.85")

        val headline = dashboardService.build().headline

        assertThat(headline.nextMonthExpected).isEqualTo(KrwPair(60480, 51408))
    }

    @Test
    @DisplayName("최신 환율이 없는데 미국 종목 예상이 필요하면 종목과 지급 월을 담아 실패한다")
    fun usd_forecast_without_latest_rate_fails_with_symbol_and_month() {
        val schd = usHolding(brokerage, "SCHD", "120")
        profileFor(schd, "0.36", "12", "0.85")

        assertThatThrownBy { dashboardService.build() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("USD 최신 환율 없음: SCHD 2026-12")
    }

    @Test
    @DisplayName("원화·달러가 아닌 배당 정보로 예상해야 하면 통화와 종목과 지급 월을 담아 실패한다")
    fun forecast_in_other_currency_fails_with_currency_symbol_and_month() {
        val vti = usHolding(brokerage, "VTI", "10")
        profileIn(vti, "EUR", "1.5", "12")

        assertThatThrownBy { dashboardService.build() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("원화로 바꿀 수 없는 예상 통화: EUR VTI 2026-12")
    }

    @Test
    @DisplayName("12월에는 다음 해 1월 예상을 다음 달 예상으로 준다")
    fun next_month_expected_in_december_is_january_of_next_year() {
        clock.set(OffsetDateTime.parse("2026-12-10T10:00+09:00").toInstant())
        val gana = krHolding(brokerage, "900110", "10")
        profileFor(gana, "500", "1", "1")

        val headline = dashboardService.build().headline

        assertThat(headline.nextMonthExpected).isEqualTo(KrwPair(5000, 5000))
    }

    @Test
    @DisplayName("이번 달이 지급 월인데 아직 안 들어온 종목은 올해 총합 예상에 들어간다")
    fun unreceived_payment_of_this_month_is_in_year_total() {
        val gana = krHolding(isa, "900110", "10")
        profileFor(gana, "500", "11", "1")

        val headline = dashboardService.build().headline

        assertThat(headline.yearTotalExpected).isEqualTo(KrwPair(5000, 5000))
    }

    @Test
    @DisplayName("이번 달 입금이 이미 있으면 그 종목의 이번 달 예상은 더하지 않는다")
    fun received_payment_of_this_month_replaces_its_expected() {
        val gana = krHolding(isa, "900110", "10")
        profileFor(gana, "500", "11", "1")
        krwDividendIn(isa, "900110", "5000", "5000", "2026-11-20")

        val headline = dashboardService.build().headline

        assertThat(headline.yearTotalExpected).isEqualTo(KrwPair(5000, 5000))
    }

    @Test
    @DisplayName("올해 총합 예상은 12월까지 남은 달의 예상을 더하고 다음 해 예상은 넣지 않는다")
    fun year_total_has_remaining_months_of_this_year_only() {
        clock.set(OffsetDateTime.parse("2026-10-15T10:00+09:00").toInstant())
        val gana = krHolding(brokerage, "900110", "10")
        profileFor(gana, "500", "1,11,12", "1")

        val headline = dashboardService.build().headline

        assertThat(headline.yearTotalExpected).isEqualTo(KrwPair(10000, 10000))
    }

    @Test
    @DisplayName("전량 매도한 종목은 받은 배당에 남고 예상과 보유 목록에서는 빠진다")
    fun sold_out_holding_stays_in_received_but_leaves_forecast_and_holdings() {
        val gana = krHolding(brokerage, "900110", "0")
        profileFor(gana, "1000", "3,12", "0.846")
        krwDividendIn(brokerage, "900110", "10000", "8460", "2026-03-10")

        val response = dashboardService.build()

        assertThat(response.headline.received.gross).isEqualTo(10000L)
        assertThat(response.headline.nextMonthExpected).isEqualTo(KrwPair(0, 0))
        assertThat(response.holdings).isEmpty()
    }

    @Test
    @DisplayName("데이터가 없어도 월 막대는 1월부터 12월까지 12개이고 구간은 비어 있다")
    fun months_are_twelve_bars_with_no_segments_without_data() {
        val months = dashboardService.build().months

        assertThat(months.map { it.month }).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        assertThat(months).allSatisfy { assertThat(it.segments).isEmpty() }
    }

    @Test
    @DisplayName("지난달 막대에는 계좌 종류별 실제 구간만 있다")
    fun past_month_bar_has_only_actual_segments_per_account_kind() {
        val etf = krHolding(brokerage, "0000A0", "100")
        profileFor(etf, "50", "3,12", "1")
        krwDividendIn(brokerage, "900110", "10000", "8460", "2026-03-10")
        krwDividendIn(isa, "0000A0", "3000", "3000", "2026-03-20")

        val response = dashboardService.build()

        assertThat(response.month(3).segments).containsExactlyInAnyOrder(
            MonthSegment(AccountKind.BROKERAGE, KrwPair(10000, 8460), true),
            MonthSegment(AccountKind.ISA, KrwPair(3000, 3000), true),
        )
    }

    @Test
    @DisplayName("이번 달 막대에는 받은 배당은 실제 구간, 아직 안 받은 예상은 예상 구간으로 들어간다")
    fun this_month_bar_splits_received_and_unreceived() {
        val gana = krHolding(brokerage, "900110", "10")
        profileFor(gana, "500", "11", "0.846")
        krwDividendIn(brokerage, "900110", "5000", "4230", "2026-11-05")
        val etf = krHolding(brokerage, "0000A0", "100")
        profileFor(etf, "50", "11", "1")

        val response = dashboardService.build()

        assertThat(response.month(11).segments).containsExactlyInAnyOrder(
            MonthSegment(AccountKind.BROKERAGE, KrwPair(5000, 4230), true),
            MonthSegment(AccountKind.BROKERAGE, KrwPair(5000, 5000), false),
        )
    }

    @Test
    @DisplayName("다른 계좌에서 같은 종목 배당을 받았어도 이 계좌의 이번 달 예상은 남는다")
    fun received_payment_in_other_account_keeps_expected_of_this_account() {
        val brokerageGana = krHolding(brokerage, "900110", "10")
        profileFor(brokerageGana, "500", "11", "1")
        val isaGana = krHolding(isa, "900110", "10")
        profileFor(isaGana, "500", "11", "1")
        krwDividendIn(isa, "900110", "5000", "5000", "2026-11-20")

        val response = dashboardService.build()

        assertThat(response.month(11).segments).containsExactlyInAnyOrder(
            MonthSegment(AccountKind.BROKERAGE, KrwPair(5000, 5000), false),
            MonthSegment(AccountKind.ISA, KrwPair(5000, 5000), true),
        )
    }

    @Test
    @DisplayName("다음 달부터의 막대에는 예상 구간만 있다")
    fun future_month_bar_has_only_expected_segments() {
        val etf = krHolding(isa, "0000A0", "20")
        profileFor(etf, "50", "12", "1")

        val response = dashboardService.build()

        assertThat(response.month(12).segments).containsExactly(
            MonthSegment(AccountKind.ISA, KrwPair(1000, 1000), false),
        )
    }

    @Test
    @DisplayName("같은 달 구간은 계좌 종류 순서이고 같은 종류면 실제 구간이 먼저다")
    fun segments_are_ordered_by_account_kind_then_actual_first() {
        val pensionEtf = krHolding(pension, "0000A0", "10")
        profileFor(pensionEtf, "50", "11", "1")
        val isaEtf = krHolding(isa, "0000A0", "20")
        profileFor(isaEtf, "50", "11", "1")
        val brokerageEtf = krHolding(brokerage, "0000A0", "100")
        profileFor(brokerageEtf, "50", "11", "1")
        krwDividendIn(brokerage, "900110", "5000", "4230", "2026-11-05")

        val response = dashboardService.build()

        assertThat(response.month(11).segments).containsExactly(
            MonthSegment(AccountKind.BROKERAGE, KrwPair(5000, 4230), true),
            MonthSegment(AccountKind.BROKERAGE, KrwPair(5000, 5000), false),
            MonthSegment(AccountKind.ISA, KrwPair(1000, 1000), false),
            MonthSegment(AccountKind.PENSION_SAVINGS, KrwPair(500, 500), false),
        )
    }

    @Test
    @DisplayName("보유 종목 행은 그 계좌의 가장 최근 지급일과 그날 받은 배당의 원화 합을 준다")
    fun holding_row_has_latest_payment_date_of_its_account_and_that_day_total() {
        krHolding(brokerage, "900110", "10")
        krwDividendIn(brokerage, "900110", "3000", "2538", "2025-09-10")
        krwDividendIn(brokerage, "900110", "4000", "3384", "2025-12-10")
        krwDividendIn(brokerage, "900110", "1000", "846", "2025-12-10")
        krwDividendIn(isa, "900110", "2000", "2000", "2026-03-10")

        val row = dashboardService.build().row(brokerage, "900110")

        assertThat(row.lastPaidDate).isEqualTo(LocalDate.parse("2025-12-10"))
        assertThat(row.lastPaid).isEqualTo(KrwPair(5000, 4230))
    }

    @Test
    @DisplayName("배당 기록이 없는 보유 종목 행은 최근 지급일과 금액이 비어 있다")
    fun holding_without_dividend_has_no_last_payment() {
        krHolding(brokerage, "900110", "10")

        val row = dashboardService.build().row(brokerage, "900110")

        assertThat(row.lastPaidDate).isNull()
        assertThat(row.lastPaid).isNull()
    }

    @Test
    @DisplayName("배당 정보가 없는 보유 종목은 배당 정보 입력 필요로 표시하고 주당 배당·지급 월·출처를 비운다")
    fun holding_without_profile_needs_dividend_info() {
        krHolding(isa, "0000A0", "20")

        val row = dashboardService.build().row(isa, "0000A0")

        assertThat(row.needsDividendInfo).isTrue()
        assertThat(row.perShare).isNull()
        assertThat(row.nextPayMonth).isNull()
        assertThat(row.profileSource).isNull()
    }

    @Test
    @DisplayName("배당 정보가 있는 보유 종목 행은 주당 배당과 통화와 출처를 준다")
    fun holding_with_profile_shows_per_share_currency_and_source() {
        latestUsdRate("1400")
        val schd = usHolding(brokerage, "SCHD", "120")
        profileFor(schd, "0.36", "3,6,9,12", "0.85", ProfileSource.MANUAL)

        val row = dashboardService.build().row(brokerage, "SCHD")

        assertThat(row.needsDividendInfo).isFalse()
        assertThat(row.perShare).isEqualByComparingTo("0.36")
        assertThat(row.currency).isEqualTo("USD")
        assertThat(row.profileSource).isEqualTo(ProfileSource.MANUAL)
    }

    @Test
    @DisplayName("배당 정보 통화가 보유 종목 통화와 다르면 행의 통화는 주당 배당의 통화인 배당 정보 통화다")
    fun holding_row_currency_is_profile_currency_when_it_differs_from_holding() {
        val schd = usHolding(brokerage, "SCHD", "120")
        profileIn(schd, "KRW", "497", "3,6,9,12")

        val row = dashboardService.build().row(brokerage, "SCHD")

        assertThat(row.perShare).isEqualByComparingTo("497")
        assertThat(row.currency).isEqualTo("KRW")
    }

    @Test
    @DisplayName("이번 달이 지급 월이고 아직 안 받았으면 다음 지급 월은 이번 달이다")
    fun next_pay_month_is_this_month_when_not_received_yet() {
        val gana = krHolding(brokerage, "900110", "10")
        profileFor(gana, "500", "2,11", "1")

        val row = dashboardService.build().row(brokerage, "900110")

        assertThat(row.nextPayMonth).isEqualTo(11)
    }

    @Test
    @DisplayName("이번 달 배당을 이미 받았으면 다음 지급 월은 다음 해의 첫 지급 월이다")
    fun next_pay_month_wraps_to_next_year_after_receiving_this_month() {
        val gana = krHolding(brokerage, "900110", "10")
        profileFor(gana, "500", "2,11", "1")
        krwDividendIn(brokerage, "900110", "5000", "5000", "2026-11-20")

        val row = dashboardService.build().row(brokerage, "900110")

        assertThat(row.nextPayMonth).isEqualTo(2)
    }

    @Test
    @DisplayName("연 1회 지급 종목이 이번 달 배당을 이미 받았으면 다음 지급 월은 다음 해 같은 달이다")
    fun next_pay_month_of_annual_payer_is_same_month_next_year_after_receiving() {
        val gana = krHolding(brokerage, "900110", "10")
        profileFor(gana, "500", "11", "1")
        krwDividendIn(brokerage, "900110", "5000", "5000", "2026-11-20")

        val row = dashboardService.build().row(brokerage, "900110")

        assertThat(row.nextPayMonth).isEqualTo(11)
    }

    @Test
    @DisplayName("보유 목록은 계좌 종류 순서이고 같은 종류면 종목 코드 순서다")
    fun holdings_are_ordered_by_account_kind_then_symbol() {
        krHolding(pension, "900110", "1")
        krHolding(isa, "900110", "1")
        krHolding(brokerage, "900110", "1")
        krHolding(brokerage, "0000A0", "1")

        val holdings = dashboardService.build().holdings

        assertThat(holdings.map { it.accountKind to it.symbol }).containsExactly(
            AccountKind.BROKERAGE to "0000A0",
            AccountKind.BROKERAGE to "900110",
            AccountKind.ISA to "900110",
            AccountKind.PENSION_SAVINGS to "900110",
        )
    }

    @Test
    @DisplayName("동기화 기록이 없으면 마지막 성공 시각이 비어 있다")
    fun last_succeeded_at_is_empty_without_sync_runs() {
        val sync = dashboardService.build().sync

        assertThat(sync.lastSucceededAt).isNull()
    }

    @Test
    @DisplayName("동기화 기록이 없으면 마지막 실행 시작 시각이 비어 있다")
    fun last_started_at_is_empty_without_sync_runs() {
        val sync = dashboardService.build().sync

        assertThat(sync.lastStartedAt).isNull()
    }

    @Test
    @DisplayName("마지막 실행이 실패면 상태와 사유를 같이 준다")
    fun last_failed_run_gives_status_and_reason() {
        syncRun(SyncStatus.FAILED, "2026-11-22T03:00+09:00", "2026-11-22T03:00:30+09:00", "동기화 실패: 토스 응답 지연")
        syncRun(SyncStatus.SUCCEEDED, "2026-11-21T03:00+09:00", "2026-11-21T03:01+09:00", null)

        val sync = dashboardService.build().sync

        assertThat(sync.lastStatus).isEqualTo(SyncStatus.FAILED)
        assertThat(sync.lastFailureReason).isEqualTo("동기화 실패: 토스 응답 지연")
    }

    @Test
    @DisplayName("마지막 성공 시각은 가장 최근 성공 실행의 종료 시각이다")
    fun last_succeeded_at_is_finish_time_of_latest_succeeded_run() {
        syncRun(SyncStatus.SUCCEEDED, "2026-11-20T03:00+09:00", "2026-11-20T03:01+09:00", null)
        syncRun(SyncStatus.SUCCEEDED, "2026-11-21T03:00+09:00", "2026-11-21T03:02+09:00", null)
        syncRun(SyncStatus.FAILED, "2026-11-22T03:00+09:00", "2026-11-22T03:00:30+09:00", "동기화 실패: 토스 응답 지연")

        val sync = dashboardService.build().sync

        assertThat(sync.lastSucceededAt).isAtSameInstantAs(OffsetDateTime.parse("2026-11-21T03:02+09:00"))
    }

    private fun DashboardResponse.month(month: Int): MonthBar {
        assertThat(months).describedAs("${month}월 막대").filteredOn { it.month == month }.hasSize(1)
        return months.first { it.month == month }
    }

    private fun DashboardResponse.row(accountId: Long, symbol: String): HoldingRow {
        assertThat(holdings).describedAs("계좌 $accountId 의 $symbol 보유 행")
            .filteredOn { it.accountId == accountId && it.symbol == symbol }
            .hasSize(1)
        return holdings.first { it.accountId == accountId && it.symbol == symbol }
    }

    private fun dividendIn(accountId: Long, currency: String, amount: String, net: String, date: String) {
        ledgerRepository.save(dividend("900110", amount, net, currency, date).apply { this.accountId = accountId })
    }

    private fun krwDividendIn(accountId: Long, symbol: String, amount: String, net: String, date: String) {
        ledgerRepository.save(dividend(symbol, amount, net, "KRW", date).apply { this.accountId = accountId })
    }

    private fun usdDividendIn(accountId: Long, amount: String, net: String, rate: String?, date: String) {
        ledgerRepository.save(
            dividend("SCHD", amount, net, "USD", date).apply {
                this.accountId = accountId
                exchangeRate = rate?.let { BigDecimal(it) }
            },
        )
    }

    private fun cancelledDividendIn(accountId: Long, amount: String, net: String, date: String) {
        ledgerRepository.save(
            dividend("900110", amount, net, "KRW", date).apply {
                this.accountId = accountId
                isCancellation = true
            },
        )
    }

    private fun interestIn(accountId: Long, amount: String, net: String, date: String) {
        ledgerRepository.save(
            LedgerEntry(accountId, LedgerEntryType.INTEREST, "KRW", LocalDate.parse(date), LedgerSource.TOSS).apply {
                this.amount = BigDecimal(amount)
                netAmount = BigDecimal(net)
            },
        )
    }

    private fun krHolding(accountId: Long, symbol: String, quantity: String): Holding = holdingRepository.save(
        Holding(accountId, symbol, "테스트 국내 종목", "KR", "KRW", BigDecimal(quantity)).apply {
            updatedAt = OffsetDateTime.parse("2026-11-22T06:00+09:00")
        },
    )

    private fun usHolding(accountId: Long, symbol: String, quantity: String): Holding = holdingRepository.save(
        Holding(accountId, symbol, "테스트 배당 ETF", "US", "USD", BigDecimal(quantity)).apply {
            updatedAt = OffsetDateTime.parse("2026-11-22T06:00+09:00")
        },
    )

    private fun profileFor(
        holding: Holding,
        perShare: String,
        payMonths: String,
        netRatio: String,
        source: ProfileSource = ProfileSource.AUTO,
    ) {
        profileRepository.save(
            DividendProfile(
                holding.accountId,
                holding.symbol,
                BigDecimal(perShare),
                holding.currency,
                payMonths,
                BigDecimal(netRatio),
            ).apply {
                this.source = source
                updatedAt = OffsetDateTime.parse("2026-11-01T09:00+09:00")
            },
        )
    }

    private fun profileIn(holding: Holding, currency: String, perShare: String, payMonths: String) {
        profileRepository.save(
            DividendProfile(
                holding.accountId,
                holding.symbol,
                BigDecimal(perShare),
                currency,
                payMonths,
                BigDecimal.ONE,
            )
                .apply {
                    source = ProfileSource.AUTO
                    updatedAt = OffsetDateTime.parse("2026-11-01T09:00+09:00")
                },
        )
    }

    private fun latestUsdRate(rate: String) {
        fxRateRepository.save(FxRate("USD", BigDecimal(rate), OffsetDateTime.parse("2026-11-22T06:00+09:00")))
    }

    private fun syncRun(status: SyncStatus, startedAt: String, finishedAt: String, failureReason: String?) {
        syncRunRepository.save(
            SyncRun(SyncTrigger.SCHEDULED, status, OffsetDateTime.parse(startedAt)).apply {
                this.finishedAt = OffsetDateTime.parse(finishedAt)
                this.failureReason = failureReason
            },
        )
    }
}
