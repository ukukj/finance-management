package com.investment.dashboard.account

import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate

data class CreateAccountRequest(
    val kind: AccountKind,
    @field:NotBlank
    @field:Size(max = 50)
    val name: String,
    val isaType: IsaType? = null,
    val isaOpenDate: LocalDate? = null,
    val isaMaturityDate: LocalDate? = null,
    @field:PositiveOrZero
    @field:Digits(integer = 16, fraction = 4)
    val priorContributionTotal: BigDecimal? = null,
    @field:PositiveOrZero
    @field:Digits(integer = 16, fraction = 4)
    val contributedThisYear: BigDecimal? = null,
)

data class UpdateAccountRequest(
    @field:Pattern(regexp = "(?s).*\\S.*")
    @field:Size(max = 50)
    val name: String? = null,
    val isaType: IsaType? = null,
    val isaOpenDate: LocalDate? = null,
    val isaMaturityDate: LocalDate? = null,
    @field:PositiveOrZero
    @field:Digits(integer = 16, fraction = 4)
    val priorContributionTotal: BigDecimal? = null,
)

data class AccountCreated(val id: Long)

data class AccountView(
    val id: Long,
    val kind: AccountKind,
    val broker: Broker,
    val name: String,
    val isaType: IsaType?,
    val isaOpenDate: LocalDate?,
    val isaMaturityDate: LocalDate?,
    val priorContributionTotal: BigDecimal,
)

fun Account.toView(): AccountView = AccountView(
    id = checkNotNull(id),
    kind = kind,
    broker = broker,
    name = name,
    isaType = isaType,
    isaOpenDate = isaOpenDate,
    isaMaturityDate = isaMaturityDate,
    priorContributionTotal = priorContributionTotal,
)
