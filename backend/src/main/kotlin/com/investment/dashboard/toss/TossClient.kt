package com.investment.dashboard.toss

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

class TossClient(
    restClientBuilder: RestClient.Builder,
    private val properties: TossProperties,
    private val sleeper: Sleeper,
) : TossApi {

    private val restClient = restClientBuilder
        .baseUrl(properties.baseUrl)
        .defaultStatusHandler(HttpStatusCode::isError) { _, response -> throw toTossException(response) }
        .build()

    private var token: String? = null

    override fun accounts(): List<TossAccount> = parsingResponse {
        get(null, "/api/v1/accounts").required("result").values().map { it.toAccount() }
    }

    override fun holdings(accountSeq: Long): List<TossHolding> = parsingResponse {
        get(accountSeq, "/api/v1/holdings").required("result").required("items").values().map { it.toHolding() }
    }

    override fun allTransactions(accountSeq: Long): List<TossTransaction> = parsingResponse {
        val transactions = mutableListOf<TossTransaction>()
        var cursor: String? = null
        do {
            if (cursor != null) {
                sleeper.sleep(PAGE_INTERVAL)
            }
            val page = transactionPage(accountSeq, cursor)
            page.required("transactions").values().mapTo(transactions) { it.toTransaction() }
            cursor = if (page.required("hasNext").booleanValue()) page.string("nextCursor") else null
        } while (cursor != null)
        transactions
    }

    override fun usdKrwRate(): BigDecimal = parsingResponse {
        get(null, "/api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW").required("result").decimal("rate")
    }

    private fun transactionPage(accountSeq: Long, cursor: String?): JsonNode {
        val page = if (cursor == null) {
            get(accountSeq, "/api/v1/transactions?limit={limit}", mapOf("limit" to PAGE_SIZE))
        } else {
            get(
                accountSeq,
                "/api/v1/transactions?limit={limit}&cursor={cursor}",
                mapOf("limit" to PAGE_SIZE, "cursor" to cursor),
            )
        }
        return page.required("result")
    }

    private fun get(accountSeq: Long?, uri: String, uriVariables: Map<String, Any> = emptyMap()): JsonNode =
        authorized { accessToken ->
            retryingOn429 {
                val body = translatingTransportErrors {
                    restClient.get()
                        .uri(uri, uriVariables)
                        .headers { headers ->
                            headers.setBearerAuth(accessToken)
                            if (accountSeq != null) {
                                headers.set(ACCOUNT_HEADER, accountSeq.toString())
                            }
                        }
                        .retrieve()
                        .body<JsonNode>()
                }
                body ?: throw TossMalformedResponseException("토스 응답 본문 없음: $uri")
            }
        }

    private fun <T> authorized(call: (String) -> T): T {
        val accessToken = token ?: issueToken()
        return try {
            call(accessToken)
        } catch (e: TossApiException) {
            if (e.status != HttpStatus.UNAUTHORIZED.value()) {
                throw e
            }
            call(issueToken())
        }
    }

    private fun <T> retryingOn429(call: () -> T): T {
        repeat(MAX_RETRIES_ON_429) {
            try {
                return call()
            } catch (e: RateLimitedException) {
                sleeper.sleep(e.retryAfter)
            }
        }
        return call()
    }

    private fun issueToken(): String {
        if (properties.clientId.isBlank()) {
            throw TossApiException(0, "missing-credentials", "토스 키 없음")
        }
        val form = LinkedMultiValueMap<String, String>()
        form.add("grant_type", "client_credentials")
        form.add("client_id", properties.clientId)
        form.add("client_secret", properties.clientSecret)
        val body = retryingOn429 {
            translatingTransportErrors {
                restClient.post()
                    .uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body<JsonNode>()
            }
        }
        val issued = (body ?: throw TossMalformedResponseException("토스 토큰 응답 본문 없음")).string("access_token")
        token = issued
        return issued
    }

    private fun <T> translatingTransportErrors(call: () -> T): T = try {
        call()
    } catch (e: RestClientException) {
        throw TossApiException(0, TRANSPORT_ERROR_CODE, "토스 API 호출 실패: ${e.message}", e)
    }
}

private class RateLimitedException(val retryAfter: Duration, code: String, message: String) :
    TossApiException(HttpStatus.TOO_MANY_REQUESTS.value(), code, message)

private const val ACCOUNT_HEADER = "X-Tossinvest-Account"
private const val PAGE_SIZE = 100
private const val MAX_RETRIES_ON_429 = 2
private const val UNKNOWN_ERROR_CODE = "unknown"
private const val TRANSPORT_ERROR_CODE = "transport-error"

// 거래내역 API 초당 5회 제한에 맞추도록
private val PAGE_INTERVAL = Duration.ofMillis(200)
private val DEFAULT_RETRY_AFTER = Duration.ofSeconds(1)
private val IP_BLOCKED_CODES = setOf("ip-not-allowed", "access_denied")

private fun toTossException(response: ClientHttpResponse): TossApiException {
    val status = response.statusCode.value()
    val error = try {
        JsonMapper.shared().readTree(response.body)?.get("error")
    } catch (e: JacksonException) {
        log.debug("토스 오류 응답 본문이 JSON 이 아님", e)
        null
    }
    val codeNode = if (error?.isString == true) error else error?.get("code")
    val code = codeNode?.stringValue() ?: UNKNOWN_ERROR_CODE
    val message = error?.get("message")?.stringValue() ?: "토스 API 오류 (HTTP $status)"
    val ipBlocked = status == HttpStatus.FORBIDDEN.value() && code in IP_BLOCKED_CODES
    return when {
        ipBlocked -> TossIpNotAllowedException(status, code, message)
        status == HttpStatus.TOO_MANY_REQUESTS.value() -> RateLimitedException(retryAfter(response), code, message)
        else -> TossApiException(status, code, message)
    }
}

private fun retryAfter(response: ClientHttpResponse): Duration =
    response.headers.getFirst(HttpHeaders.RETRY_AFTER)?.toLongOrNull()?.let(Duration::ofSeconds) ?: DEFAULT_RETRY_AFTER

private fun JsonNode.toAccount() = TossAccount(
    accountNo = string("accountNo"),
    accountSeq = required("accountSeq").longValue(),
    accountType = string("accountType"),
)

private fun JsonNode.toHolding() = TossHolding(
    symbol = string("symbol"),
    name = string("name"),
    marketCountry = string("marketCountry"),
    currency = string("currency"),
    quantity = decimal("quantity"),
    averagePurchasePrice = decimalOrNull("averagePurchasePrice"),
)

private fun JsonNode.toTransaction() = TossTransaction(
    transactionType = string("transactionType"),
    marketCountry = string("marketCountry"),
    currency = string("currency"),
    symbol = stringOrNull("symbol"),
    assetClass = stringOrNull("assetClass"),
    isinCode = stringOrNull("isinCode"),
    quantity = decimalOrNull("quantity"),
    filledPrice = decimalOrNull("filledPrice"),
    amount = decimalOrNull("amount"),
    commission = decimal("commission"),
    tax = decimal("tax"),
    netAmount = decimalOrNull("netAmount"),
    exchangeRate = decimalOrNull("exchangeRate"),
    settlementDate = LocalDate.parse(string("settlementDate")),
    settled = required("settled").booleanValue(),
    transactionTime = stringOrNull("transactionTime")?.let(OffsetDateTime::parse),
    isCancellation = required("isCancellation").booleanValue(),
    rawJson = toString(),
)

private fun JsonNode.string(field: String): String =
    stringOrNull(field) ?: throw TossMalformedResponseException("토스 응답에 $field 값 없음")

private fun JsonNode.stringOrNull(field: String): String? = get(field)?.stringValue()

private fun JsonNode.decimal(field: String) = decimalOf(field, string(field))

private fun JsonNode.decimalOrNull(field: String) = stringOrNull(field)?.let { decimalOf(field, it) }

private fun decimalOf(field: String, value: String) =
    value.toBigDecimalOrNull() ?: throw TossMalformedResponseException("토스 응답의 $field 값이 숫자가 아님: $value")

private inline fun <T> parsingResponse(parse: () -> T): T = try {
    parse()
} catch (e: JacksonException) {
    throw TossMalformedResponseException("토스 응답 형식 오류: ${e.originalMessage}", e)
} catch (e: DateTimeParseException) {
    throw TossMalformedResponseException("토스 응답 날짜 형식 오류: ${e.parsedString}", e)
}

private val log = LoggerFactory.getLogger(TossClient::class.java)
