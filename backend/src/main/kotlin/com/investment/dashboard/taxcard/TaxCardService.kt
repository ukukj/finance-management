package com.investment.dashboard.taxcard

import com.investment.dashboard.account.Account
import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.ledger.LedgerEntryType
import com.investment.dashboard.taxrule.TaxRuleKey
import com.investment.dashboard.taxrule.TaxRuleMissingException
import com.investment.dashboard.taxrule.TaxRules
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

@Service
class TaxCardService(
    private val accountRepository: AccountRepository,
    private val ledgerEntryRepository: LedgerEntryRepository,
    private val taxRules: TaxRules,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun build(): TaxCardResponse {
        val today = LocalDate.now(clock)
        return TaxCardResponse(pension = pensionCard(today), isa = isaCard(today))
    }

    private fun pensionCard(today: LocalDate): Card<PensionFigures> {
        val account = firstAccount(AccountKind.PENSION_SAVINGS) ?: return noAccount()
        val accountId = checkNotNull(account.id)
        val deposits = depositsOf(accountId, today)
        return withRules(accountId) {
            val creditLimit = taxRules.get(today.year, TaxRuleKey.PENSION_SAVINGS_CREDIT_LIMIT)
            TaxCardCalculator.pension(deposits.thisYear, creditLimit, today)
        }
    }

    private fun isaCard(today: LocalDate): Card<IsaFigures> {
        val account = firstAccount(AccountKind.ISA) ?: return noAccount()
        val accountId = checkNotNull(account.id)
        val openDate = account.isaOpenDate
        val maturityDate = account.isaMaturityDate
        check(openDate != null && maturityDate != null) { "ISA 가입일·만기일 없는 계좌: $accountId" }
        val deposits = depositsOf(accountId, today)
        val terms = IsaTerms(openDate, maturityDate, account.priorContributionTotal.add(deposits.beforeThisYear))
        return withRules(accountId) {
            val rules = IsaRules(
                annualLimit = taxRules.get(today.year, TaxRuleKey.ISA_ANNUAL_LIMIT),
                totalLimit = taxRules.get(today.year, TaxRuleKey.ISA_TOTAL_LIMIT),
                transferDays = transferDays(today.year),
            )
            TaxCardCalculator.isa(terms, rules, deposits.thisYear, today)
        }
    }

    private fun firstAccount(kind: AccountKind): Account? =
        accountRepository.findByKind(kind).minByOrNull { checkNotNull(it.id) }

    private fun depositsOf(accountId: Long, today: LocalDate): Deposits {
        val yearStart = today.withDayOfYear(1)
        val deposits = ledgerEntryRepository.findByAccountIdOrderBySettlementDateAsc(accountId)
            .filter { it.type == LedgerEntryType.DEPOSIT && !it.isCancellation && !it.settlementDate.isAfter(today) }
        val (beforeThisYear, thisYear) = deposits.partition { it.settlementDate.isBefore(yearStart) }
        return Deposits(beforeThisYear = beforeThisYear.total(), thisYear = thisYear.total())
    }

    private fun transferDays(year: Int): Int {
        val days = taxRules.get(year, TaxRuleKey.ISA_PENSION_TRANSFER_DAYS)
        return try {
            days.intValueExact()
        } catch (e: ArithmeticException) {
            val value = days.stripTrailingZeros().toPlainString()
            throw IllegalStateException("${year}년 ${TaxRuleKey.ISA_PENSION_TRANSFER_DAYS} 값이 정수가 아님: $value", e)
        }
    }
}

private class Deposits(val beforeThisYear: BigDecimal, val thisYear: BigDecimal)

private fun <T> noAccount(): Card<T> = Card(CardStatus.NO_ACCOUNT, null, null, null)

private fun <T> withRules(accountId: Long, figures: () -> T): Card<T> = try {
    Card(CardStatus.OK, accountId, figures(), null)
} catch (e: TaxRuleMissingException) {
    Card(CardStatus.RULES_MISSING, accountId, null, e.year)
}

private fun List<LedgerEntry>.total(): BigDecimal = mapNotNull { it.amount }.fold(BigDecimal.ZERO, BigDecimal::add)
