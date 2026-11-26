package com.investment.dashboard.taxcard

import java.math.BigDecimal
import java.time.LocalDate

data class PensionFigures(
    val paidThisYear: Long,
    val creditLimit: Long,
    val remaining: Long,
    val daysLeft: Long,
    val monthlyNeeded: Long,
)

data class IsaFigures(
    val annualAvailable: Long,
    val paidThisYear: Long,
    val remaining: Long,
    val maturityDate: LocalDate,
    val transferDeadline: LocalDate,
)

data class IsaTerms(val openDate: LocalDate, val maturityDate: LocalDate, val priorTotal: BigDecimal)

data class IsaRules(val annualLimit: BigDecimal, val totalLimit: BigDecimal, val transferDays: Int)

enum class CardStatus { OK, RULES_MISSING, NO_ACCOUNT }

data class Card<T>(val status: CardStatus, val accountId: Long?, val figures: T?, val missingRuleYear: Int?)

data class TaxCardResponse(val pension: Card<PensionFigures>, val isa: Card<IsaFigures>)
