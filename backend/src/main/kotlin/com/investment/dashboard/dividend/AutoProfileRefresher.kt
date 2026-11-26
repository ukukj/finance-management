package com.investment.dashboard.dividend

import com.investment.dashboard.holding.HoldingRepository
import com.investment.dashboard.ledger.LedgerEntryRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

@Component
class AutoProfileRefresher(
    private val holdingRepository: HoldingRepository,
    private val ledgerEntryRepository: LedgerEntryRepository,
    private val profileRepository: DividendProfileRepository,
    private val clock: Clock,
) {

    @Transactional
    fun refresh(accountId: Long, today: LocalDate) {
        val entries = ledgerEntryRepository.findByAccountIdOrderBySettlementDateAsc(accountId)
        val existing = profileRepository.findByAccountId(accountId).associateBy { it.symbol }
        val now = OffsetDateTime.now(clock)
        holdingRepository.findByAccountId(accountId).forEach { holding ->
            val estimate = DividendEstimator.autoProfile(entries, holding.symbol, today)
            val current = existing[holding.symbol]
            when {
                estimate != null && autoWins(current, estimate) -> saveAuto(accountId, current, estimate, now)
                estimate == null && current?.source == ProfileSource.AUTO -> profileRepository.delete(current)
            }
        }
    }

    private fun autoWins(current: DividendProfile?, estimate: AutoProfile): Boolean = current == null ||
        current.source == ProfileSource.AUTO ||
        current.updatedAt.atZoneSameInstant(clock.zone).toLocalDate().isBefore(estimate.perShareBasisDate)

    private fun saveAuto(accountId: Long, current: DividendProfile?, auto: AutoProfile, now: OffsetDateTime) {
        val months = auto.payMonths.joinToString(",")
        val profile = current
            ?: DividendProfile(accountId, auto.symbol, auto.perShare, auto.currency, months, auto.netRatio)
        profile.perShare = auto.perShare
        profile.currency = auto.currency
        profile.payMonths = months
        profile.netRatio = auto.netRatio
        profile.source = ProfileSource.AUTO
        profile.updatedAt = now
        profileRepository.save(profile)
    }
}
