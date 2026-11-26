package com.investment.dashboard.manual

import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.account.requireAccount
import com.investment.dashboard.dashboard.KrwConverter
import com.investment.dashboard.dividend.DividendProfileRepository
import com.investment.dashboard.holding.HoldingRepository
import com.investment.dashboard.ledger.LedgerEntryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

@Service
class PendingDividendService(
    private val accountRepository: AccountRepository,
    private val holdingRepository: HoldingRepository,
    private val ledgerEntryRepository: LedgerEntryRepository,
    private val profileRepository: DividendProfileRepository,
    private val krwConverter: KrwConverter,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun pendingDividends(accountId: Long): List<PendingDividendView> {
        val account = accountRepository.requireAccount(accountId)
        val pending = PendingDividends.of(
            kind = account.kind,
            holdings = holdingRepository.findByAccountId(accountId),
            profiles = profileRepository.findByAccountId(accountId),
            entries = ledgerEntryRepository.findByAccountIdOrderBySettlementDateAsc(accountId),
            today = LocalDate.now(clock),
        )
        return pending.map { PendingDividendView(it.symbol, it.month, krwConverter.forecast(it).gross) }
    }
}
