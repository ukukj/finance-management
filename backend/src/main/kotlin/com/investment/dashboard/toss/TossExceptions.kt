package com.investment.dashboard.toss

open class TossApiException(val status: Int, val code: String, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class TossIpNotAllowedException(status: Int, code: String, message: String) :
    TossApiException(status, code, message)

class TossMalformedResponseException(message: String, cause: Throwable? = null) :
    TossApiException(0, MALFORMED_RESPONSE_CODE, message, cause)

private const val MALFORMED_RESPONSE_CODE = "malformed-response"
