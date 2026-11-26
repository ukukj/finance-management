package com.investment.dashboard.dividend

import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.account.requireAccount
import com.investment.dashboard.common.InvalidRequestException
import com.investment.dashboard.holding.HoldingRepository
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.ledger.isValidDividend
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

@Service
class DividendProfileService(
    private val accountRepository: AccountRepository,
    private val holdingRepository: HoldingRepository,
    private val ledgerEntryRepository: LedgerEntryRepository,
    private val profileRepository: DividendProfileRepository,
    private val clock: Clock,
) {

    @Transactional
    fun putProfile(accountId: Long, symbol: String, request: PutDividendProfileRequest) {
        accountRepository.requireAccount(accountId)
        val payMonths = payMonthsOf(request.payMonths)
        val currency = holdingRepository.findByAccountIdAndSymbol(accountId, symbol)?.currency ?: KRW
        val profile = profileRepository.findByAccountIdAndSymbol(accountId, symbol)
            ?: DividendProfile(accountId, symbol, request.perShare, currency, payMonths, BigDecimal.ONE)
        profile.perShare = request.perShare
        profile.currency = currency
        profile.payMonths = payMonths
        profile.netRatio = BigDecimal.ONE
        saveManual(profile)
    }

    @Transactional
    fun applyManualDividend(accountId: Long, symbol: String, amount: BigDecimal, date: LocalDate) {
        val quantity = holdingRepository.findByAccountIdAndSymbol(accountId, symbol)?.quantity
        if (quantity == null || quantity.signum() <= 0 || laterDividendExists(accountId, symbol, date)) {
            return
        }
        val perShare = amount.divide(quantity, PER_SHARE_SCALE, RoundingMode.HALF_UP)
        val profile = profileRepository.findByAccountIdAndSymbol(accountId, symbol)
            ?: DividendProfile(accountId, symbol, perShare, KRW, "${date.monthValue}", BigDecimal.ONE)
        profile.perShare = perShare
        saveManual(profile)
    }

    private fun laterDividendExists(accountId: Long, symbol: String, date: LocalDate): Boolean {
        val today = LocalDate.now(clock)
        return ledgerEntryRepository.findByAccountIdOrderBySettlementDateAsc(accountId).any {
            it.symbol == symbol && it.isValidDividend(today) && it.settlementDate.isAfter(date)
        }
    }

    private fun saveManual(profile: DividendProfile) {
        profile.source = ProfileSource.MANUAL
        profile.updatedAt = OffsetDateTime.now(clock)
        profileRepository.save(profile)
    }
}

private fun payMonthsOf(months: List<Int>): String {
    val valid = months.isNotEmpty() && months.all { it in 1..MONTHS_IN_YEAR } && months.distinct().size == months.size
    if (!valid) {
        throw InvalidRequestException(mapOf("payMonths" to "1~12 사이 서로 다른 월 1개 이상"))
    }
    return months.sorted().joinToString(",")
}

private const val KRW = "KRW"
private const val PER_SHARE_SCALE = 6
private const val MONTHS_IN_YEAR = 12
