package com.investment.dashboard.toss

open class TossApiException(val status: Int, val code: String, message: String) : RuntimeException(message)

class TossIpNotAllowedException(status: Int, code: String, message: String) :
    TossApiException(status, code, message)
