package com.investment.dashboard.common

class ManualEntryNotAllowedException(message: String) : RuntimeException(message)

class NotFoundException(message: String) : RuntimeException(message)

class InvalidRequestException(val fields: Map<String, String>) : RuntimeException("요청 값 오류: ${fields.keys}")
