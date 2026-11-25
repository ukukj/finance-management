package com.investment.dashboard.toss

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.core.JacksonException
import java.time.format.DateTimeParseException

class TossClientMalformedResponseTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = TossClient(
        builder,
        TossProperties(BASE_URL, "test-client-id", "test-client-secret"),
        Sleeper {},
    )

    @Test
    @DisplayName("숫자 자리에 숫자가 아닌 값이 오면 TossMalformedResponseException 이다")
    fun non_numeric_amount_is_malformed_response() {
        respondToHoldings("""{"result":{"items":[${holding(quantity = "\"12주\"")}]}}""")

        val error = catchThrowable { client.holdings(1L) }

        assertThat(error).isInstanceOf(TossMalformedResponseException::class.java)
            .hasMessage("토스 응답의 quantity 값이 숫자가 아님: 12주")
        server.verify()
    }

    @Test
    @DisplayName("값의 JSON 타입이 다르면 원인을 담은 TossMalformedResponseException 이다")
    fun wrong_json_type_is_malformed_response_with_cause() {
        respondToHoldings("""{"result":{"items":[${holding(quantity = "12")}]}}""")

        val error = catchThrowable { client.holdings(1L) }

        assertThat(error).isInstanceOf(TossMalformedResponseException::class.java)
            .hasMessageStartingWith("토스 응답 형식 오류: ")
            .hasCauseInstanceOf(JacksonException::class.java)
        server.verify()
    }

    @Test
    @DisplayName("날짜 형식이 틀리면 원인을 담은 TossMalformedResponseException 이다")
    fun unparsable_date_is_malformed_response_with_cause() {
        expectToken()
        server.expect(requestTo("$BASE_URL/api/v1/transactions?limit=100"))
            .andRespond(
                withSuccess(
                    """{"result":{"transactions":[$SLASH_DATE_ROW],"nextCursor":null,"hasNext":false}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val error = catchThrowable { client.allTransactions(1L) }

        assertThat(error).isInstanceOf(TossMalformedResponseException::class.java)
            .hasMessage("토스 응답 날짜 형식 오류: 2025/03/02")
            .hasCauseInstanceOf(DateTimeParseException::class.java)
        server.verify()
    }

    private fun respondToHoldings(body: String) {
        expectToken()
        server.expect(requestTo("$BASE_URL/api/v1/holdings"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private fun expectToken() {
        server.expect(requestTo("$BASE_URL/oauth2/token"))
            .andRespond(
                withSuccess(
                    """{"access_token":"t1","token_type":"Bearer","expires_in":86400}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
    }

    private fun holding(quantity: String) =
        """
        {"symbol":"900110","name":"가나전자","marketCountry":"KR","currency":"KRW","quantity":$quantity,
        "averagePurchasePrice":"45200"}
        """

    private companion object {
        const val BASE_URL = "https://toss.test"

        const val SLASH_DATE_ROW = """
            {"transactionType":"DEPOSIT","marketCountry":"KR","currency":"KRW","symbol":null,"assetClass":null,
            "isinCode":null,"quantity":null,"filledPrice":null,"amount":"50000","commission":"0","tax":"0",
            "netAmount":"50000","exchangeRate":null,"settlementDate":"2025/03/02","settled":true,
            "transactionTime":null,"isCancellation":false}
        """
    }
}
