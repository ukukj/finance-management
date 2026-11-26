package com.investment.dashboard.account

import com.investment.dashboard.common.InvalidRequestException
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.ledger.manualDeposit
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val ledgerEntryRepository: LedgerEntryRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(): List<AccountView> = accountRepository.findAll(Sort.by("id")).map { it.toView() }

    @Transactional
    fun create(request: CreateAccountRequest): Long {
        val errors = kindErrors(request.kind) +
            isaErrors(request.kind, request.isaType, request.isaOpenDate, request.isaMaturityDate)
        if (errors.isNotEmpty()) {
            throw InvalidRequestException(errors)
        }
        val account = Account(request.kind, Broker.MIRAE, request.name)
        account.isaType = request.isaType
        account.isaOpenDate = request.isaOpenDate
        account.isaMaturityDate = request.isaMaturityDate
        account.priorContributionTotal = request.priorContributionTotal ?: BigDecimal.ZERO
        val id = checkNotNull(accountRepository.save(account).id)
        val contributed = request.contributedThisYear
        if (contributed != null && contributed.signum() > 0) {
            val deposit = manualDeposit(id, LocalDate.now(clock), contributed)
            deposit.memo = INITIAL_DEPOSIT_MEMO
            ledgerEntryRepository.save(deposit)
        }
        return id
    }

    @Transactional
    fun update(id: Long, request: UpdateAccountRequest): AccountView {
        val account = accountRepository.requireAccount(id)
        val isaType = request.isaType ?: account.isaType
        val openDate = request.isaOpenDate ?: account.isaOpenDate
        val maturityDate = request.isaMaturityDate ?: account.isaMaturityDate
        val errors = isaErrors(account.kind, isaType, openDate, maturityDate)
        if (errors.isNotEmpty()) {
            throw InvalidRequestException(errors)
        }
        account.name = request.name ?: account.name
        account.isaType = isaType
        account.isaOpenDate = openDate
        account.isaMaturityDate = maturityDate
        account.priorContributionTotal = request.priorContributionTotal ?: account.priorContributionTotal
        return account.toView()
    }
}

private fun kindErrors(kind: AccountKind): Map<String, String> =
    if (kind == AccountKind.BROKERAGE) mapOf("kind" to "토스 계좌는 동기화가 만듦") else emptyMap()

private fun isaErrors(
    kind: AccountKind,
    isaType: IsaType?,
    openDate: LocalDate?,
    maturityDate: LocalDate?,
): Map<String, String> {
    val values = mapOf("isaType" to isaType, "isaOpenDate" to openDate, "isaMaturityDate" to maturityDate)
    if (kind != AccountKind.ISA) {
        return values.filterValues { it != null }.mapValues { "ISA 계좌만 입력 가능" }
    }
    val missing = values.filterValues { it == null }.mapValues { "ISA 계좌에 필요" }
    val maturityNotAfterOpen = openDate != null && maturityDate != null && !maturityDate.isAfter(openDate)
    return missing + if (maturityNotAfterOpen) mapOf("isaMaturityDate" to "가입일 뒤여야 함") else emptyMap()
}

private const val INITIAL_DEPOSIT_MEMO = "초기 입력"
