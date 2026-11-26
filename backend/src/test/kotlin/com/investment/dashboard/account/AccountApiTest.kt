package com.investment.dashboard.account

import com.investment.dashboard.TestcontainersConfiguration
import com.investment.dashboard.dividend.DividendProfileRepository
import com.investment.dashboard.holding.HoldingRepository
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.ledger.LedgerEntryType
import com.investment.dashboard.ledger.LedgerSource
import com.investment.dashboard.support.MutableClock
import com.investment.dashboard.support.TestClockConfiguration
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.OffsetDateTime

@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
@WithMockUser
class AccountApiTest {

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

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun reset() {
        profileRepository.deleteAllInBatch()
        ledgerRepository.deleteAllInBatch()
        holdingRepository.deleteAllInBatch()
        accountRepository.deleteAllInBatch()
        clock.set(OffsetDateTime.parse("2026-11-22T10:00+09:00").toInstant())
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test
    @DisplayName("ISA 계좌는 유형·가입일·만기일 없이 만들 수 없다")
    fun isa_account_requires_type_open_date_and_maturity_date() {
        val result = mvc.post("/api/accounts") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"ISA","name":"미래에셋 ISA"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactlyInAnyOrder("isaType", "isaOpenDate", "isaMaturityDate")
        assertThat(accountRepository.count()).isZero()
    }

    @Test
    @DisplayName("올해 납입액을 주면 초기 입력 메모의 입금 행이 생긴다")
    fun contributed_this_year_creates_initial_deposit() {
        val id = createAccount("""{"kind":"PENSION_SAVINGS","name":"미래에셋 연금저축","contributedThisYear":4700000}""")

        val entries = ledgerRepository.findByAccountIdOrderBySettlementDateAsc(id)
        assertThat(entries).hasSize(1)
        val deposit = entries.first()
        assertThat(deposit.type).isEqualTo(LedgerEntryType.DEPOSIT)
        assertThat(deposit.memo).isEqualTo("초기 입력")
        assertThat(deposit.settlementDate).isEqualTo(LocalDate.parse("2026-11-22"))
        assertThat(deposit.amount).isEqualByComparingTo("4700000")
        assertThat(deposit.netAmount).isEqualByComparingTo("4700000")
        assertThat(deposit.tax).isEqualByComparingTo("0")
        assertThat(deposit.currency).isEqualTo("KRW")
        assertThat(deposit.source).isEqualTo(LedgerSource.MANUAL)
        assertThat(deposit.symbol).isNull()
    }

    @Test
    @DisplayName("올해 납입액이 0 이면 입금 행을 만들지 않는다")
    fun zero_contributed_this_year_creates_no_deposit() {
        val id = createAccount("""{"kind":"PENSION_SAVINGS","name":"미래에셋 연금저축","contributedThisYear":0}""")

        assertThat(ledgerRepository.findByAccountIdOrderBySettlementDateAsc(id)).isEmpty()
    }

    @Test
    @DisplayName("올해 납입액이 음수면 400 이다")
    fun negative_contributed_this_year_is_400() {
        val result = mvc.post("/api/accounts") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"PENSION_SAVINGS","name":"미래에셋 연금저축","contributedThisYear":-1}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("contributedThisYear")
        assertThat(accountRepository.count()).isZero()
    }

    @Test
    @DisplayName("BROKERAGE 계좌를 직접 만들면 400 이다")
    fun creating_brokerage_account_is_400() {
        val result = mvc.post("/api/accounts") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"BROKERAGE","name":"일반 계좌"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("kind")
        assertThat(accountRepository.count()).isZero()
    }

    @Test
    @DisplayName("ISA 를 만들면 201 과 id 를 주고 목록에 미래에셋 계좌로 나온다")
    fun created_isa_is_listed_as_mirae_account() {
        val id = createAccount(
            """{"kind":"ISA","name":"미래에셋 ISA","isaType":"GENERAL","isaOpenDate":"2024-03-01",
                |"isaMaturityDate":"2027-03-01","priorContributionTotal":15000000}
            """.trimMargin(),
        )

        mvc.get("/api/accounts").andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(id) }
            content {
                json(
                    """[{"kind":"ISA","broker":"MIRAE","name":"미래에셋 ISA","isaType":"GENERAL",
                        |"isaOpenDate":"2024-03-01","isaMaturityDate":"2027-03-01","priorContributionTotal":15000000}]
                    """.trimMargin(),
                )
            }
        }
    }

    @Test
    @DisplayName("연금저축 계좌에 ISA 전용 값을 주면 400 이다")
    fun isa_fields_on_pension_account_are_400() {
        val result = mvc.post("/api/accounts") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"PENSION_SAVINGS","name":"미래에셋 연금저축","isaType":"GENERAL","isaOpenDate":"2024-03-01"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactlyInAnyOrder("isaType", "isaOpenDate")
        assertThat(accountRepository.count()).isZero()
    }

    @Test
    @DisplayName("ISA 만기일이 가입일보다 늦지 않으면 400 이다")
    fun isa_maturity_date_not_after_open_date_is_400() {
        val result = mvc.post("/api/accounts") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"ISA","name":"미래에셋 ISA","isaType":"GENERAL","isaOpenDate":"2024-03-01",
                |"isaMaturityDate":"2024-03-01"}
            """.trimMargin()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("isaMaturityDate")
    }

    @Test
    @DisplayName("계좌 이름이 50자를 넘으면 400 이다")
    fun account_name_longer_than_50_is_400() {
        val result = mvc.post("/api/accounts") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"PENSION_SAVINGS","name":"${"가".repeat(51)}"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("name")
    }

    @Test
    @DisplayName("계좌 종류가 알 수 없는 값이면 400 이고 kind 를 알려 준다")
    fun unknown_account_kind_is_400_with_kind_field() {
        val result = mvc.post("/api/accounts") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"IRP","name":"미래에셋 IRP"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("kind")
    }

    @Test
    @DisplayName("본문이 JSON 이 아니면 400 INVALID_REQUEST 다")
    fun malformed_body_is_400() {
        val result = mvc.post("/api/accounts") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"ISA","""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).isEmpty()
    }

    @Test
    @DisplayName("계좌 이름이 공백이면 400 이다")
    fun blank_account_name_is_400() {
        val result = mvc.post("/api/accounts") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"PENSION_SAVINGS","name":"   "}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("name")
        assertThat(accountRepository.count()).isZero()
    }

    @Test
    @DisplayName("이전 누적 납입액이 음수면 400 이다")
    fun negative_prior_contribution_total_is_400() {
        val result = mvc.post("/api/accounts") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"PENSION_SAVINGS","name":"미래에셋 연금저축","priorContributionTotal":-1}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("priorContributionTotal")
        assertThat(accountRepository.count()).isZero()
    }

    @Test
    @DisplayName("Content-Type 없이 계좌를 만들면 415 UNSUPPORTED_MEDIA_TYPE 이다")
    fun create_account_without_content_type_is_415() {
        mvc.post("/api/accounts") {
            with(csrf())
            content = """{"kind":"PENSION_SAVINGS","name":"미래에셋 연금저축"}"""
        }.andExpect {
            status { isUnsupportedMediaType() }
            jsonPath("$.code") { value("UNSUPPORTED_MEDIA_TYPE") }
        }

        assertThat(accountRepository.count()).isZero()
    }

    @Test
    @DisplayName("계좌 목록 경로에 DELETE 를 보내면 405 METHOD_NOT_ALLOWED 다")
    fun delete_on_accounts_is_405() {
        mvc.delete("/api/accounts") {
            with(csrf())
        }.andExpect {
            status { isMethodNotAllowed() }
            jsonPath("$.code") { value("METHOD_NOT_ALLOWED") }
        }
    }

    @Test
    @DisplayName("계좌를 고칠 때 보내지 않은 값은 그대로 두고 고친 계좌를 돌려준다")
    fun patch_changes_given_values_and_keeps_missing_ones() {
        val id = createAccount(
            """{"kind":"ISA","name":"미래에셋 ISA","isaType":"GENERAL","isaOpenDate":"2024-03-01",
                |"isaMaturityDate":"2027-03-01","priorContributionTotal":15000000}
            """.trimMargin(),
        )

        mvc.patch("/api/accounts/$id") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"ISA 새 이름","priorContributionTotal":20000000,"isaType":null}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(id) }
            content {
                json(
                    """{"kind":"ISA","broker":"MIRAE","name":"ISA 새 이름","isaType":"GENERAL",
                        |"isaOpenDate":"2024-03-01","isaMaturityDate":"2027-03-01","priorContributionTotal":20000000}
                    """.trimMargin(),
                )
            }
        }
    }

    @Test
    @DisplayName("ISA 만기일을 가입일 이전으로 고치면 400 이다")
    fun patch_maturity_date_before_stored_open_date_is_400() {
        val id = createAccount(
            """{"kind":"ISA","name":"미래에셋 ISA","isaType":"GENERAL","isaOpenDate":"2024-03-01",
                |"isaMaturityDate":"2027-03-01"}
            """.trimMargin(),
        )

        val result = mvc.patch("/api/accounts/$id") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"isaMaturityDate":"2024-02-01"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("isaMaturityDate")
        assertThat(accountRepository.findByIdOrNull(id)?.isaMaturityDate).isEqualTo(LocalDate.parse("2027-03-01"))
    }

    @Test
    @DisplayName("연금저축 계좌에 ISA 가입일을 고쳐 넣으면 400 이다")
    fun patch_isa_field_on_pension_account_is_400() {
        val id = createAccount("""{"kind":"PENSION_SAVINGS","name":"미래에셋 연금저축"}""")

        val result = mvc.patch("/api/accounts/$id") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"isaOpenDate":"2024-03-01"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("isaOpenDate")
        assertThat(accountRepository.findByIdOrNull(id)?.isaOpenDate).isNull()
    }

    @Test
    @DisplayName("계좌 이름을 공백으로 고치면 400 이다")
    fun patch_blank_account_name_is_400() {
        val id = createAccount("""{"kind":"PENSION_SAVINGS","name":"미래에셋 연금저축"}""")

        val result = mvc.patch("/api/accounts/$id") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"   "}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("name")
        assertThat(accountRepository.findByIdOrNull(id)?.name).isEqualTo("미래에셋 연금저축")
    }

    @Test
    @DisplayName("계좌 id 가 숫자가 아니면 400 이고 id 를 알려 준다")
    fun non_numeric_account_id_is_400_with_id_field() {
        val result = mvc.patch("/api/accounts/abc") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"새 이름"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }.andReturn()

        assertThat(fieldKeysOf(result)).containsExactly("id")
    }

    @Test
    @DisplayName("없는 계좌를 고치면 404 NOT_FOUND 다")
    fun patch_unknown_account_is_404() {
        mvc.patch("/api/accounts/999999") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"새 이름"}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    private fun createAccount(body: String): Long {
        val result = mvc.post("/api/accounts") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isCreated() }
        }.andReturn()
        return JsonPath.parse(result.response.getContentAsString(StandardCharsets.UTF_8)).read("$.id", Long::class.java)
    }

    private fun fieldKeysOf(result: MvcResult): Set<String> =
        JsonPath.read<Map<String, Any>>(result.response.getContentAsString(StandardCharsets.UTF_8), "$.fields").keys
}
