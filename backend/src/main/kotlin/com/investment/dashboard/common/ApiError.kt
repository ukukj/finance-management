package com.investment.dashboard.common

data class ApiError(val code: String, val message: String, val fields: Map<String, String> = emptyMap())
