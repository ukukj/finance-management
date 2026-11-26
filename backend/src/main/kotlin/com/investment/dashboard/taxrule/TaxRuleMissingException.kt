package com.investment.dashboard.taxrule

class TaxRuleMissingException(val year: Int) : RuntimeException("${year}년 세법 값 없음")
