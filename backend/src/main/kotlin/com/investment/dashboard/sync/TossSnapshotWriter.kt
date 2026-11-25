package com.investment.dashboard.sync

import com.investment.dashboard.account.Account
import com.investment.dashboard.account.AccountKind
import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.account.Broker
import com.investment.dashboard.fx.FxRate
import com.investment.dashboard.fx.FxRateRepository
import com.investment.dashboard.holding.Holding
import com.investment.dashboard.holding.HoldingRepository
import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.ledger.LedgerEntryType
import com.investment.dashboard.ledger.LedgerSource
import com.investment.dashboard.toss.TossAccount
import com.investment.dashboard.toss.TossHolding
import com.investment.dashboard.toss.TossTransaction
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.OffsetDateTime

@Component
class TossSnapshotWriter(
    private val accountRepository: AccountRepository,
    private val holdingRepository: HoldingRepository,
    private val ledgerEntryRepository: LedgerEntryRepository,
    private val fxRateRepository: FxRateRepository,
    private val clock: Clock,
) {

    @Transactional
    fun write(
        account: TossAccount,
        holdings: List<TossHolding>,
        transactions: List<TossTransaction>,
        usdKrwRate: BigDecimal,
    ) {
        val now = OffsetDateTime.now(clock)
        val accountId = checkNotNull(findOrCreate(account).id)
        holdingRepository.deleteByAccountId(accountId)
        holdingRepository.saveAll(holdings.map { it.toHolding(accountId, now) })
        ledgerEntryRepository.deleteByAccountIdAndSource(accountId, LedgerSource.TOSS)
        ledgerEntryRepository.saveAll(transactions.map { it.toLedgerEntry(accountId) })
        fxRateRepository.save(FxRate(USD, usdKrwRate, now))
    }

    private fun findOrCreate(account: TossAccount): Account = accountRepository.findByTossAccountSeq(account.accountSeq)
        ?: accountRepository.save(
            Account(AccountKind.BROKERAGE, Broker.TOSS, TOSS_ACCOUNT_NAME).also {
                it.tossAccountSeq = account.accountSeq
            },
        )
}

private fun TossHolding.toHolding(accountId: Long, now: OffsetDateTime): Holding {
    val holding = Holding(accountId, symbol, name, marketCountry, currency, quantity)
    holding.averagePrice = averagePurchasePrice
    holding.updatedAt = now
    return holding
}

private fun TossTransaction.toLedgerEntry(accountId: Long): LedgerEntry {
    val entry = LedgerEntry(accountId, ledgerType(), currency, settlementDate, LedgerSource.TOSS)
    entry.symbol = symbol
    entry.quantity = quantity
    entry.amount = amount
    entry.tax = tax
    entry.netAmount = netAmount
    entry.exchangeRate = exchangeRate
    entry.isCancellation = isCancellation
    entry.raw = rawJson
    return entry
}

private fun TossTransaction.ledgerType(): LedgerEntryType {
    val type = LedgerEntryType.entries.firstOrNull { it.name == transactionType }
        ?: throw SyncDomainException.unknownTransactionType(transactionType)
    return if (type == LedgerEntryType.DEPOSIT && assetClass == STOCK_ASSET_CLASS) LedgerEntryType.DIVIDEND else type
}

private const val TOSS_ACCOUNT_NAME = "토스 일반"
private const val STOCK_ASSET_CLASS = "STOCK"
private const val USD = "USD"
