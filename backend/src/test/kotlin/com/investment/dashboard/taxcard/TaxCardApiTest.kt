package com.investment.dashboard.taxcard

import com.investment.dashboard.TestcontainersConfiguration
import com.investment.dashboard.account.Account
import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.account.Broker
import com.investment.dashboard.account.IsaType
import com.investment.dashboard.dividend.DividendProfileRepository
import com.investment.dashboard.holding.HoldingRepository
import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.ledger.LedgerEntryType
import com.investment.dashboard.ledger.LedgerSource
import com.investment.dashboard.ledger.manualDeposit
import com.investment.dashboard.support.MutableClock
import com.investment.dashboard.support.TestClockConfiguration
import com.investment.dashboard.taxrule.TaxRule
import com.investment.dashboard.taxrule.TaxRuleKey
import com.investment.dashboard.taxrule.TaxRuleRepository
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
class TaxCardApiTest {

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
    lateinit var taxRuleRepository: TaxRuleRepository

    @Autowired
    lateinit var taxCardService: TaxCardService

    private lateinit var mvc: MockMvc

    private val addedRules = mutableListOf<TaxRule>()

    @BeforeEach
    fun reset() {
        profileRepository.deleteAllInBatch()
        ledgerRepository.deleteAllInBatch()
        holdingRepository.deleteAllInBatch()
        accountRepository.deleteAllInBatch()
        clock.set(OffsetDateTime.parse("2026-11-22T10:00+09:00").toInstant())
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @AfterEach
    fun removeRulesAddedByTests() {
        taxRuleRepository.deleteAllInBatch(addedRules)
    }

    @Test
    @DisplayName("로그인 없이 부르면 401")
    fun tax_cards_without_login_is_401() {
        mvc.get("/api/tax-cards").andExpect {
            status { isUnauthorized() }
            content { string("") }
        }
    }

    @Test
    @WithMockUser
    @DisplayName("연금저축 계좌가 없으면 NO_ACCOUNT 다")
    fun pension_card_without_pension_account_is_no_account() {
        isaAccount("2024-03-01", "2027-03-01", "15000000")

        mvc.get("/api/tax-cards").andExpect {
            status { isOk() }
            jsonPath("$.pension") { value(NO_ACCOUNT_CARD) }
        }
    }

    @Test
    @WithMockUser
    @DisplayName("ISA 계좌가 없으면 NO_ACCOUNT 다")
    fun isa_card_without_isa_account_is_no_account() {
        pensionAccount()

        mvc.get("/api/tax-cards").andExpect {
            status { isOk() }
            jsonPath("$.isa") { value(NO_ACCOUNT_CARD) }
        }
    }

    @Test
    @WithMockUser
    @DisplayName("세법 값이 없는 해에도 계좌가 없으면 NO_ACCOUNT 다")
    fun no_account_takes_precedence_over_missing_rules() {
        clock.set(OffsetDateTime.parse("2027-01-02T10:00+09:00").toInstant())

        mvc.get("/api/tax-cards").andExpect {
            status { isOk() }
            content {
                json(
                    """{"pension":{"status":"NO_ACCOUNT","accountId":null,"figures":null,"missingRuleYear":null},
                        |"isa":{"status":"NO_ACCOUNT","accountId":null,"figures":null,"missingRuleYear":null}}
                    """.trimMargin(),
                    JsonCompareMode.STRICT,
                )
            }
        }
    }

    @Test
    @WithMockUser
    @DisplayName("그해 세법 값이 없으면 RULES_MISSING 으로 응답하고 대시보드는 정상이다")
    fun missing_rules_answer_rules_missing_and_dashboard_is_ok() {
        val pensionId = pensionAccount()
        val isaId = isaAccount("2024-03-01", "2027-03-01", "15000000")
        clock.set(OffsetDateTime.parse("2027-01-02T10:00+09:00").toInstant())

        mvc.get("/api/tax-cards").andExpect {
            status { isOk() }
            content {
                json(
                    """{"pension":{"status":"RULES_MISSING","accountId":$pensionId,"figures":null,
                        |"missingRuleYear":2027},
                        |"isa":{"status":"RULES_MISSING","accountId":$isaId,"figures":null,"missingRuleYear":2027}}
                    """.trimMargin(),
                    JsonCompareMode.STRICT,
                )
            }
        }
        mvc.get("/api/dashboard").andExpect {
            status { isOk() }
        }
    }

    @Test
    @WithMockUser
    @DisplayName("계좌와 세법 값이 있으면 OK 와 계좌 id·수치를 주고 missingRuleYear 는 null 이다")
    fun cards_with_accounts_and_rules_are_ok_with_figures() {
        val pensionId = pensionAccount()
        deposit(pensionId, "2026-11-22", "4700000")
        val isaId = isaAccount("2024-03-01", "2027-03-01", "15000000")
        deposit(isaId, "2026-05-10", "5000000")

        mvc.get("/api/tax-cards").andExpect {
            status { isOk() }
            content {
                json(
                    """{"pension":{"status":"OK","accountId":$pensionId,"figures":{"paidThisYear":4700000,
                        |"creditLimit":6000000,"remaining":1300000,"daysLeft":39,"monthlyNeeded":650000},
                        |"missingRuleYear":null},
                        |"isa":{"status":"OK","accountId":$isaId,"figures":{"annualAvailable":45000000,
                        |"paidThisYear":5000000,"remaining":40000000,"maturityDate":"2027-03-01",
                        |"transferDeadline":"2027-04-30"},"missingRuleYear":null}}
                    """.trimMargin(),
                    JsonCompareMode.STRICT,
                )
            }
        }
    }

    @Test
    @WithMockUser
    @DisplayName("올해 납입액은 그 계좌의 1월 1일부터 오늘까지 취소 아닌 입금만 더한다")
    fun paid_this_year_sums_only_this_years_non_cancelled_deposits_of_the_account() {
        val pensionId = pensionAccount()
        val otherId = accountRepository.save(Account(AccountKind.BROKERAGE, Broker.TOSS, "토스 일반")).id!!
        deposit(pensionId, "2026-01-01", "1000000")
        deposit(pensionId, "2026-11-22", "2000000")
        deposit(pensionId, "2025-12-31", "10000")
        val cancelled = manualDeposit(pensionId, LocalDate.parse("2026-06-01"), BigDecimal("20000"))
        cancelled.isCancellation = true
        ledgerRepository.save(cancelled)
        val dividend = LedgerEntry(pensionId, LedgerEntryType.DIVIDEND, "KRW", JUNE_1, LedgerSource.MANUAL)
        dividend.symbol = "458730"
        dividend.amount = BigDecimal("40000")
        dividend.netAmount = BigDecimal("40000")
        ledgerRepository.save(dividend)
        deposit(pensionId, "2026-11-23", "80000")
        deposit(otherId, "2026-06-01", "160000")
        ledgerRepository.save(LedgerEntry(pensionId, LedgerEntryType.DEPOSIT, "KRW", JUNE_1, LedgerSource.TOSS))

        mvc.get("/api/tax-cards").andExpect {
            status { isOk() }
            jsonPath("$.pension.figures.paidThisYear") { value(3000000) }
        }
    }

    @Test
    @WithMockUser
    @DisplayName("같은 종류 계좌가 여럿이면 id 가 가장 작은 계좌로 카드를 만든다")
    fun card_uses_lowest_id_account_of_the_kind() {
        val firstId = pensionAccount()
        val secondId = pensionAccount()
        deposit(firstId, "2026-03-01", "1000000")
        deposit(secondId, "2026-03-01", "2000000")

        mvc.get("/api/tax-cards").andExpect {
            status { isOk() }
            jsonPath("$.pension.accountId") { value(firstId) }
            jsonPath("$.pension.figures.paidThisYear") { value(1000000) }
        }
    }

    @Test
    @WithMockUser
    @DisplayName("ISA 이전 누적은 입력한 값에 지난해까지 앱에 기록된 입금을 더한다")
    fun isa_prior_total_adds_deposits_recorded_before_this_year() {
        val isaId = isaAccount("2024-03-01", "2027-03-01", "15000000")
        deposit(isaId, "2026-05-10", "5000000")
        addRules(
            TaxRule(2027, TaxRuleKey.ISA_ANNUAL_LIMIT, BigDecimal("20000000")),
            TaxRule(2027, TaxRuleKey.ISA_TOTAL_LIMIT, BigDecimal("100000000")),
            TaxRule(2027, TaxRuleKey.ISA_PENSION_TRANSFER_DAYS, BigDecimal("60")),
        )
        clock.set(OffsetDateTime.parse("2027-02-01T10:00+09:00").toInstant())

        mvc.get("/api/tax-cards").andExpect {
            status { isOk() }
            jsonPath("$.isa.figures.annualAvailable") { value(60000000) }
            jsonPath("$.isa.figures.paidThisYear") { value(0) }
        }
    }

    @Test
    @WithMockUser
    @DisplayName("지난해 12월 31일 입금은 이전 누적에, 올해 1월 1일 입금은 올해 납입에 들어간다")
    fun deposits_split_into_prior_total_and_paid_this_year_at_january_1() {
        val isaId = isaAccount("2024-03-01", "2027-03-01", "15000000")
        deposit(isaId, "2025-12-31", "3000000")
        deposit(isaId, "2026-01-01", "2000000")

        mvc.get("/api/tax-cards").andExpect {
            status { isOk() }
            jsonPath("$.isa.figures.annualAvailable") { value(42000000) }
            jsonPath("$.isa.figures.paidThisYear") { value(2000000) }
        }
    }

    @Test
    @WithMockUser
    @DisplayName("취소된 입금·다른 계좌 입금·입금 아닌 행은 ISA 이전 누적에 넣지 않는다")
    fun isa_prior_total_excludes_cancelled_other_account_and_non_deposit_rows() {
        val isaId = isaAccount("2024-03-01", "2027-03-01", "15000000")
        val pensionId = pensionAccount()
        deposit(isaId, "2025-06-01", "3000000")
        val cancelled = manualDeposit(isaId, LAST_JUNE_1, BigDecimal("100000"))
        cancelled.isCancellation = true
        ledgerRepository.save(cancelled)
        deposit(pensionId, "2025-06-01", "200000")
        val dividend = LedgerEntry(isaId, LedgerEntryType.DIVIDEND, "KRW", LAST_JUNE_1, LedgerSource.MANUAL)
        dividend.symbol = "458730"
        dividend.amount = BigDecimal("400000")
        dividend.netAmount = BigDecimal("400000")
        ledgerRepository.save(dividend)
        ledgerRepository.save(LedgerEntry(isaId, LedgerEntryType.DEPOSIT, "KRW", LAST_JUNE_1, LedgerSource.TOSS))

        mvc.get("/api/tax-cards").andExpect {
            status { isOk() }
            jsonPath("$.isa.figures.annualAvailable") { value(42000000) }
        }
    }

    @Test
    @DisplayName("가입일·만기일 없는 ISA 계좌면 계좌 id 를 담은 IllegalStateException 이 난다")
    fun isa_account_without_dates_fails_naming_the_account() {
        val isaId = accountRepository.save(Account(AccountKind.ISA, Broker.MIRAE, "미래에셋 ISA")).id!!

        assertThatThrownBy { taxCardService.build() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("ISA 가입일·만기일 없는 계좌: $isaId")
    }

    @Test
    @DisplayName("연금 이전 기한 일수에 소수가 있으면 키와 연도를 담은 IllegalStateException 이 난다")
    fun fractional_transfer_days_fail_naming_key_and_year() {
        addRules(
            TaxRule(2030, TaxRuleKey.ISA_ANNUAL_LIMIT, BigDecimal("20000000")),
            TaxRule(2030, TaxRuleKey.ISA_TOTAL_LIMIT, BigDecimal("100000000")),
            TaxRule(2030, TaxRuleKey.ISA_PENSION_TRANSFER_DAYS, BigDecimal("60.5")),
        )
        isaAccount("2028-03-01", "2031-03-01", "0")
        clock.set(OffsetDateTime.parse("2030-03-01T10:00+09:00").toInstant())

        assertThatThrownBy { taxCardService.build() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("2030년 ISA_PENSION_TRANSFER_DAYS 값이 정수가 아님: 60.5")
            .hasCauseInstanceOf(ArithmeticException::class.java)
    }

    private fun addRules(vararg rules: TaxRule) {
        addedRules += taxRuleRepository.saveAll(rules.asList())
    }

    private fun pensionAccount(): Long =
        accountRepository.save(Account(AccountKind.PENSION_SAVINGS, Broker.MIRAE, "미래에셋 연금저축")).id!!

    private fun isaAccount(openDate: String, maturityDate: String, priorTotal: String): Long {
        val account = Account(AccountKind.ISA, Broker.MIRAE, "미래에셋 ISA")
        account.isaType = IsaType.GENERAL
        account.isaOpenDate = LocalDate.parse(openDate)
        account.isaMaturityDate = LocalDate.parse(maturityDate)
        account.priorContributionTotal = BigDecimal(priorTotal)
        return accountRepository.save(account).id!!
    }

    private fun deposit(accountId: Long, date: String, amount: String) {
        ledgerRepository.save(manualDeposit(accountId, LocalDate.parse(date), BigDecimal(amount)))
    }

    private companion object {
        val NO_ACCOUNT_CARD =
            mapOf("status" to "NO_ACCOUNT", "accountId" to null, "figures" to null, "missingRuleYear" to null)

        val JUNE_1: LocalDate = LocalDate.parse("2026-06-01")

        val LAST_JUNE_1: LocalDate = LocalDate.parse("2025-06-01")
    }
}
