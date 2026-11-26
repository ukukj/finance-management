package com.investment.dashboard.support

import com.investment.dashboard.account.Account
import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.account.Broker
import com.investment.dashboard.dividend.DividendProfileRepository
import com.investment.dashboard.holding.Holding
import com.investment.dashboard.holding.HoldingRepository
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

open class ManualApiTestSupport {

    @Autowired
    protected lateinit var context: WebApplicationContext

    @Autowired
    protected lateinit var clock: MutableClock

    @Autowired
    protected lateinit var accountRepository: AccountRepository

    @Autowired
    protected lateinit var holdingRepository: HoldingRepository

    @Autowired
    protected lateinit var ledgerRepository: LedgerEntryRepository

    @Autowired
    protected lateinit var profileRepository: DividendProfileRepository

    protected lateinit var mvc: MockMvc

    @BeforeEach
    fun reset() {
        profileRepository.deleteAllInBatch()
        ledgerRepository.deleteAllInBatch()
        holdingRepository.deleteAllInBatch()
        accountRepository.deleteAllInBatch()
        clock.set(OffsetDateTime.parse("2026-11-22T10:00+09:00").toInstant())
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    protected fun createIsa(): Long {
        val result = mvc.post("/api/accounts") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"kind":"ISA","name":"미래에셋 ISA","isaType":"GENERAL","isaOpenDate":"2024-03-01",
                |"isaMaturityDate":"2027-03-01"}
            """.trimMargin()
        }.andExpect { status { isCreated() } }.andReturn()
        return idOf(result)
    }

    protected fun createTossAccount(): Long =
        accountRepository.save(Account(AccountKind.BROKERAGE, Broker.TOSS, "토스 일반")).id!!

    protected fun putHolding(accountId: Long, symbol: String, quantity: String) {
        mvc.put("/api/accounts/$accountId/holdings/$symbol") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"테스트 ETF","quantity":$quantity}"""
        }.andExpect { status { isOk() } }
    }

    protected fun putProfile(accountId: Long, symbol: String, perShare: String, payMonths: List<Int>) {
        mvc.put("/api/accounts/$accountId/dividend-profiles/$symbol") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"perShare":$perShare,"payMonths":$payMonths}"""
        }.andExpect { status { isOk() } }
    }

    protected fun postDividend(accountId: Long, symbol: String, date: String, amount: String): Long {
        val result = mvc.post("/api/accounts/$accountId/dividends") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"$symbol","date":"$date","amount":$amount}"""
        }.andExpect { status { isCreated() } }.andReturn()
        return idOf(result)
    }

    protected fun usdHolding(accountId: Long, symbol: String): Holding {
        val holding = Holding(accountId, symbol, "테스트 미국 ETF", "US", "USD", BigDecimal("100"))
        holding.updatedAt = OffsetDateTime.parse("2026-11-01T06:00+09:00")
        return holding
    }

    protected fun idOf(result: MvcResult): Long =
        JsonPath.parse(result.response.getContentAsString(StandardCharsets.UTF_8)).read("$.id", Long::class.java)

    protected fun fieldKeysOf(result: MvcResult): Set<String> =
        JsonPath.read<Map<String, Any>>(result.response.getContentAsString(StandardCharsets.UTF_8), "$.fields").keys
}
