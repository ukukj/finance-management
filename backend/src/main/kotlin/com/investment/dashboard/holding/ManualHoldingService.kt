package com.investment.dashboard.holding

import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.account.requireManualAccount
import com.investment.dashboard.common.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime

@Service
class ManualHoldingService(
    private val accountRepository: AccountRepository,
    private val holdingRepository: HoldingRepository,
    private val clock: Clock,
) {

    @Transactional
    fun put(accountId: Long, symbol: String, request: PutHoldingRequest) {
        accountRepository.requireManualAccount(accountId)
        val holding = holdingRepository.findByAccountIdAndSymbol(accountId, symbol)
            ?: Holding(accountId, symbol, request.name, KR, KRW, request.quantity)
        holding.name = request.name
        holding.quantity = request.quantity
        holding.updatedAt = OffsetDateTime.now(clock)
        holdingRepository.save(holding)
    }

    @Transactional
    fun delete(accountId: Long, symbol: String) {
        accountRepository.requireManualAccount(accountId)
        val holding = holdingRepository.findByAccountIdAndSymbol(accountId, symbol)
            ?: throw NotFoundException("보유 종목 없음: $accountId $symbol")
        holdingRepository.delete(holding)
    }
}

private const val KR = "KR"
private const val KRW = "KRW"
