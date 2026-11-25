package com.investment.dashboard.toss

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

class TossClientTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val sleeps = mutableListOf<Duration>()
    private val client = TossClient(
        builder,
        TossProperties(BASE_URL, "test-client-id", "test-client-secret"),
        Sleeper { sleeps += it },
    )

    @Test
    @DisplayName("토큰을 받은 뒤 보유종목 items 를 돌려준다")
    fun returns_holding_items_after_issuing_token() {
        server.expect(requestTo("$BASE_URL/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().formDataContains(
                    mapOf(
                        "grant_type" to "client_credentials",
                        "client_id" to "test-client-id",
                        "client_secret" to "test-client-secret",
                    ),
                ),
            )
            .andRespond(withSuccess(tokenJson("t1"), MediaType.APPLICATION_JSON))
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer t1"))
            .andExpect(header("X-Tossinvest-Account", "1"))
            .andRespond(withSuccess(fixture("holdings.json"), MediaType.APPLICATION_JSON))

        val result = client.holdings(1L)

        assertThat(result).containsExactlyInAnyOrder(
            TossHolding("900110", "가나전자", "KR", "KRW", BigDecimal("12"), BigDecimal("45200")),
            TossHolding("900220", "다라은행", "KR", "KRW", BigDecimal("30"), BigDecimal("10350.5")),
            TossHolding("0000A0", "마바 테스트 ETF", "KR", "KRW", BigDecimal("150"), BigDecimal("10450.3")),
        )
        server.verify()
    }

    @Test
    @DisplayName("계좌 목록을 돌려준다")
    fun returns_accounts() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/accounts"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer t1"))
            .andRespond(withSuccess(fixture("accounts.json"), MediaType.APPLICATION_JSON))

        val result = client.accounts()

        assertThat(result).containsExactly(TossAccount("00000000000", 1L, "BROKERAGE"))
        server.verify()
    }

    @Test
    @DisplayName("401 을 받으면 토큰을 다시 받아 한 번만 재시도한다")
    fun retries_once_after_401() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andExpect(header("Authorization", "Bearer t1"))
            .andRespond(errorResponse(HttpStatus.UNAUTHORIZED, "token-revoked"))
        expectToken("t2")
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andExpect(header("Authorization", "Bearer t2"))
            .andRespond(withSuccess(fixture("holdings.json"), MediaType.APPLICATION_JSON))

        val result = client.holdings(1L)

        assertThat(result).hasSize(3)
        server.verify()
    }

    @Test
    @DisplayName("재발급 후에도 401 이면 TossApiException 이다")
    fun throws_when_401_persists_after_reissue() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andRespond(errorResponse(HttpStatus.UNAUTHORIZED, "token-revoked"))
        expectToken("t2")
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andRespond(errorResponse(HttpStatus.UNAUTHORIZED, "token-revoked"))

        val error = catchThrowable { client.holdings(1L) }

        assertThat(error).isInstanceOfSatisfying(TossApiException::class.java) {
            assertThat(it.status).isEqualTo(401)
            assertThat(it.code).isEqualTo("token-revoked")
        }
        server.verify()
    }

    @Test
    @DisplayName("403 ip-not-allowed 는 TossIpNotAllowedException 이다")
    fun throws_ip_not_allowed_on_403_ip_not_allowed() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andRespond(errorResponse(HttpStatus.FORBIDDEN, "ip-not-allowed"))

        val error = catchThrowable { client.holdings(1L) }

        assertThat(error).isInstanceOfSatisfying(TossIpNotAllowedException::class.java) {
            assertThat(it.status).isEqualTo(403)
            assertThat(it.code).isEqualTo("ip-not-allowed")
        }
        server.verify()
    }

    @Test
    @DisplayName("토큰 발급이 403 access_denied 면 TossIpNotAllowedException 이다")
    fun throws_ip_not_allowed_when_token_access_denied() {
        server.expect(requestTo("$BASE_URL/oauth2/token"))
            .andRespond(
                withStatus(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":"access_denied"}"""),
            )

        val error = catchThrowable { client.accounts() }

        assertThat(error).isInstanceOfSatisfying(TossIpNotAllowedException::class.java) {
            assertThat(it.status).isEqualTo(403)
            assertThat(it.code).isEqualTo("access_denied")
        }
        server.verify()
    }

    @Test
    @DisplayName("토큰 발급이 그 밖의 오류면 재시도 없이 error 값을 코드로 TossApiException 이다")
    fun throws_with_token_error_string_as_code_without_retry() {
        server.expect(requestTo("$BASE_URL/oauth2/token"))
            .andRespond(
                withStatus(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":"invalid_client"}"""),
            )

        val error = catchThrowable { client.accounts() }

        assertThat(error).isInstanceOfSatisfying(TossApiException::class.java) {
            assertThat(it).isNotInstanceOf(TossIpNotAllowedException::class.java)
            assertThat(it.status).isEqualTo(401)
            assertThat(it.code).isEqualTo("invalid_client")
        }
        server.verify()
    }

    @Test
    @DisplayName("403 이 아니면 IP 차단 코드여도 TossIpNotAllowedException 이 아니다")
    fun throws_plain_api_exception_for_ip_blocked_code_without_403() {
        server.expect(requestTo("$BASE_URL/oauth2/token"))
            .andRespond(
                withStatus(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":"access_denied"}"""),
            )

        val error = catchThrowable { client.accounts() }

        assertThat(error).isInstanceOfSatisfying(TossApiException::class.java) {
            assertThat(it).isNotInstanceOf(TossIpNotAllowedException::class.java)
            assertThat(it.status).isEqualTo(401)
            assertThat(it.code).isEqualTo("access_denied")
        }
        server.verify()
    }

    @Test
    @DisplayName("오류 본문이 JSON 이 아니면 코드 unknown 으로 TossApiException 이다")
    fun throws_with_unknown_code_when_error_body_is_not_json() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andRespond(
                withStatus(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.TEXT_HTML)
                    .body("<html><body>Bad Gateway</body></html>"),
            )

        val error = catchThrowable { client.holdings(1L) }

        assertThat(error).isInstanceOfSatisfying(TossApiException::class.java) {
            assertThat(it.status).isEqualTo(502)
            assertThat(it.code).isEqualTo("unknown")
        }
        server.verify()
    }

    @Test
    @DisplayName("키가 비어 있으면 HTTP 호출 없이 실패한다")
    fun fails_without_http_call_when_client_id_is_blank() {
        val clientWithoutKey = TossClient(builder, TossProperties(BASE_URL, "", ""), Sleeper { })

        val error = catchThrowable { clientWithoutKey.accounts() }

        assertThat(error).isInstanceOfSatisfying(TossApiException::class.java) {
            assertThat(it.status).isEqualTo(0)
            assertThat(it.code).isEqualTo("missing-credentials")
            assertThat(it.message).isEqualTo("토스 키 없음")
        }
        server.verify()
    }

    @Test
    @DisplayName("429 면 Retry-After 만큼 쉬고 재시도한다")
    fun sleeps_for_retry_after_and_retries_on_429() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andRespond(errorResponse(HttpStatus.TOO_MANY_REQUESTS, "rate-limit-exceeded").header("Retry-After", "2"))
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andRespond(withSuccess(fixture("holdings.json"), MediaType.APPLICATION_JSON))

        val result = client.holdings(1L)

        assertThat(result).hasSize(3)
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2))
        server.verify()
    }

    @Test
    @DisplayName("429 에 Retry-After 가 없으면 1초 쉬고 재시도한다")
    fun sleeps_one_second_on_429_without_retry_after() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andRespond(errorResponse(HttpStatus.TOO_MANY_REQUESTS, "rate-limit-exceeded"))
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andRespond(withSuccess(fixture("holdings.json"), MediaType.APPLICATION_JSON))

        client.holdings(1L)

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1))
        server.verify()
    }

    @Test
    @DisplayName("429 가 두 번 재시도 뒤에도 오면 TossApiException 이다")
    fun throws_when_429_persists_after_two_retries() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andRespond(errorResponse(HttpStatus.TOO_MANY_REQUESTS, "rate-limit-exceeded"))
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andRespond(errorResponse(HttpStatus.TOO_MANY_REQUESTS, "rate-limit-exceeded"))
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andRespond(errorResponse(HttpStatus.TOO_MANY_REQUESTS, "rate-limit-exceeded"))

        val error = catchThrowable { client.holdings(1L) }

        assertThat(error).isInstanceOfSatisfying(TossApiException::class.java) {
            assertThat(it.status).isEqualTo(429)
            assertThat(it.code).isEqualTo("rate-limit-exceeded")
        }
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1))
        server.verify()
    }

    @Test
    @DisplayName("토큰 발급이 429 면 Retry-After 만큼 쉬고 다시 받는다")
    fun retries_token_issuance_after_429() {
        server.expect(requestTo("$BASE_URL/oauth2/token"))
            .andRespond(
                withStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "3")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":"rate_limit_exceeded"}"""),
            )
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/accounts"))
            .andExpect(header("Authorization", "Bearer t1"))
            .andRespond(withSuccess(fixture("accounts.json"), MediaType.APPLICATION_JSON))

        val result = client.accounts()

        assertThat(result).containsExactly(TossAccount("00000000000", 1L, "BROKERAGE"))
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3))
        server.verify()
    }

    @Test
    @DisplayName("환율은 result.rate 를 돌려준다")
    fun returns_result_rate_as_usd_krw_rate() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer t1"))
            .andRespond(
                withSuccess(
                    """{"result":{"baseCurrency":"USD","quoteCurrency":"KRW","rate":"1380.5"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val rate = client.usdKrwRate()

        assertThat(rate).isEqualTo(BigDecimal("1380.5"))
        server.verify()
    }

    @Test
    @DisplayName("거래내역은 hasNext 가 false 일 때까지 커서를 따라 모두 모은다")
    fun collects_transactions_following_cursor_until_has_next_is_false() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/transactions?limit=100"))
            .andExpect(header("X-Tossinvest-Account", "1"))
            .andRespond(
                withSuccess(
                    """
                    {"result":{"transactions":[$CASH_DEPOSIT_ROW,$CASH_DEPOSIT_ROW],"nextCursor":"c1","hasNext":true}}
                    """,
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect(requestTo("$BASE_URL/api/v1/transactions?limit=100&cursor=c1"))
            .andExpect(header("X-Tossinvest-Account", "1"))
            .andRespond(
                withSuccess(
                    """{"result":{"transactions":[$CASH_DEPOSIT_ROW],"nextCursor":null,"hasNext":false}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.allTransactions(1L)

        assertThat(result).hasSize(3)
        server.verify()
    }

    @Test
    @DisplayName("거래내역 쪽 요청 사이에 200ms 쉰다")
    fun sleeps_200ms_between_transaction_pages() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/transactions?limit=100"))
            .andRespond(
                withSuccess(
                    """{"result":{"transactions":[$CASH_DEPOSIT_ROW],"nextCursor":"c1","hasNext":true}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect(requestTo("$BASE_URL/api/v1/transactions?limit=100&cursor=c1"))
            .andRespond(
                withSuccess(
                    """{"result":{"transactions":[$CASH_DEPOSIT_ROW],"nextCursor":null,"hasNext":false}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        client.allTransactions(1L)

        assertThat(sleeps).containsExactly(Duration.ofMillis(200))
        server.verify()
    }

    @Test
    @DisplayName("커서는 URL 인코딩해서 그대로 보낸다")
    fun sends_cursor_url_encoded() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/transactions?limit=100"))
            .andRespond(
                withSuccess(
                    """{"result":{"transactions":[$CASH_DEPOSIT_ROW],"nextCursor":"a+b/c=","hasNext":true}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect(requestTo("$BASE_URL/api/v1/transactions?limit=100&cursor=a%2Bb%2Fc%3D"))
            .andRespond(
                withSuccess(
                    """{"result":{"transactions":[$CASH_DEPOSIT_ROW],"nextCursor":null,"hasNext":false}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        client.allTransactions(1L)

        server.verify()
    }

    @Test
    @DisplayName("hasNext 가 true 인데 nextCursor 가 null 이면 첫 쪽을 다시 받지 않고 실패한다")
    fun fails_when_has_next_comes_without_next_cursor() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/transactions?limit=100"))
            .andRespond(
                withSuccess(
                    """{"result":{"transactions":[$CASH_DEPOSIT_ROW],"nextCursor":null,"hasNext":true}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val error = catchThrowable { client.allTransactions(1L) }

        assertThat(error).isInstanceOf(TossMalformedResponseException::class.java)
            .hasMessage("토스 응답에 nextCursor 값 없음")
        server.verify()
    }

    @Test
    @DisplayName("픽스처의 모든 거래 행이 파싱되고 rawJson 이 원본 객체다")
    fun parses_every_fixture_transaction_with_raw_json_of_original_object() {
        expectFixtureTransactionPages()

        val result = client.allTransactions(1L)

        assertThat(result).hasSize(16)
        val interest = result.single { it.transactionType == "INTEREST" }
        assertThat(JsonMapper.shared().readTree(interest.rawJson))
            .isEqualTo(JsonMapper.shared().readTree(FIXTURE_INTEREST_ROW))
        server.verify()
    }

    @Test
    @DisplayName("거래 행의 문자열 숫자와 날짜·시각을 타입에 맞춰 옮긴다")
    fun maps_transaction_fields_to_typed_values() {
        expectFixtureTransactionPages()

        val result = client.allTransactions(1L)

        val usdSell = result.single { it.symbol == "ZZFK" && it.transactionType == "SELL" }
        assertThat(usdSell).usingRecursiveComparison().ignoringFields("rawJson").isEqualTo(
            TossTransaction(
                transactionType = "SELL",
                marketCountry = "US",
                currency = "USD",
                symbol = "ZZFK",
                assetClass = "STOCK",
                isinCode = "US9898ZZ1012",
                quantity = BigDecimal("2"),
                filledPrice = BigDecimal("131.42"),
                amount = BigDecimal("262.84"),
                commission = BigDecimal("0.26"),
                tax = BigDecimal("0.01"),
                netAmount = BigDecimal("262.57"),
                exchangeRate = BigDecimal("1372.4"),
                settlementDate = LocalDate.of(2025, 5, 15),
                settled = true,
                transactionTime = OffsetDateTime.parse("2025-05-15T07:30:18.901+09:00"),
                isCancellation = false,
                rawJson = "",
            ),
        )
        server.verify()
    }

    @Test
    @DisplayName("증권 입출고 행은 amount·netAmount 가 null 이어도 파싱된다")
    fun parses_securities_transfer_row_with_null_amounts() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/transactions?limit=100"))
            .andRespond(
                withSuccess(
                    """{"result":{"transactions":[$SECURITIES_TRANSFER_IN_ROW],"nextCursor":null,"hasNext":false}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.allTransactions(1L)

        val transfer = result.single()
        assertThat(transfer.quantity).isEqualTo(BigDecimal("5"))
        assertThat(transfer.amount).isNull()
        assertThat(transfer.netAmount).isNull()
        server.verify()
    }

    @Test
    @DisplayName("미결제 매매 행은 체결가·거래 시각이 null 이어도 파싱된다")
    fun parses_unsettled_trade_row_with_null_price_and_time() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/transactions?limit=100"))
            .andRespond(
                withSuccess(
                    """{"result":{"transactions":[$UNSETTLED_BUY_ROW],"nextCursor":null,"hasNext":false}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.allTransactions(1L)

        val unsettled = result.single()
        assertThat(unsettled.settled).isFalse()
        assertThat(unsettled.filledPrice).isNull()
        assertThat(unsettled.transactionTime).isNull()
        server.verify()
    }

    private fun expectToken(accessToken: String) {
        server.expect(requestTo("$BASE_URL/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(tokenJson(accessToken), MediaType.APPLICATION_JSON))
    }

    private fun expectFixtureTransactionPages() {
        expectToken("t1")
        server.expect(requestTo("$BASE_URL/api/v1/transactions?limit=100"))
            .andRespond(withSuccess(fixture("transactions-p1.json"), MediaType.APPLICATION_JSON))
        server.expect(requestTo("$BASE_URL/api/v1/transactions?limit=100&cursor=fake-cursor-page-2"))
            .andRespond(withSuccess(fixture("transactions-p2.json"), MediaType.APPLICATION_JSON))
    }

    private fun tokenJson(accessToken: String) =
        """{"access_token":"$accessToken","token_type":"Bearer","expires_in":86400}"""

    private fun errorResponse(status: HttpStatus, code: String) = withStatus(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body("""{"error":{"requestId":"req-1","code":"$code","message":"$code 오류"}}""")

    private fun fixture(name: String) = ClassPathResource("toss/$name")

    private companion object {
        const val BASE_URL = "https://toss.test"

        const val CASH_DEPOSIT_ROW = """
            {"transactionType":"DEPOSIT","marketCountry":"KR","currency":"KRW","symbol":null,"assetClass":null,
            "isinCode":null,"quantity":null,"filledPrice":null,"amount":"50000","commission":"0","tax":"0",
            "netAmount":"50000","exchangeRate":null,"settlementDate":"2025-03-02","settled":true,
            "transactionTime":"2025-03-02T09:00:00.000+09:00","isCancellation":false}
        """

        const val SECURITIES_TRANSFER_IN_ROW = """
            {"transactionType":"SECURITIES_TRANSFER_IN","marketCountry":"KR","currency":"KRW","symbol":"900110",
            "assetClass":"STOCK","isinCode":"KR7900110001","quantity":"5","filledPrice":null,"amount":null,
            "commission":"0","tax":"0","netAmount":null,"exchangeRate":null,"settlementDate":"2025-03-03",
            "settled":true,"transactionTime":"2025-03-03T10:00:00.000+09:00","isCancellation":false}
        """

        const val UNSETTLED_BUY_ROW = """
            {"transactionType":"BUY","marketCountry":"KR","currency":"KRW","symbol":"900220","assetClass":"STOCK",
            "isinCode":"KR7900220008","quantity":"4","filledPrice":null,"amount":"39480","commission":"6",
            "tax":"0","netAmount":"39486","exchangeRate":null,"settlementDate":"2025-03-05","settled":false,
            "transactionTime":null,"isCancellation":false}
        """

        const val FIXTURE_INTEREST_ROW = """
            {"transactionType":"INTEREST","marketCountry":"KR","currency":"KRW","symbol":null,"assetClass":null,
            "isinCode":null,"quantity":null,"filledPrice":null,"amount":"412","commission":"0","tax":"63",
            "netAmount":"349","exchangeRate":null,"settlementDate":"2025-05-22","settled":true,
            "transactionTime":"2025-05-22T06:41:09.233+09:00","isCancellation":false}
        """
    }
}
