package com.investment.dashboard.manual

import com.investment.dashboard.account.AccountRepository
import com.investment.dashboard.account.requireManualAccount
import com.investment.dashboard.common.InvalidRequestException
import com.investment.dashboard.common.ManualEntryNotAllowedException
import com.investment.dashboard.common.NotFoundException
import com.investment.dashboard.dividend.DividendProfileService
import com.investment.dashboard.ledger.LedgerEntry
import com.investment.dashboard.ledger.LedgerEntryRepository
import com.investment.dashboard.ledger.LedgerEntryType
import com.investment.dashboard.ledger.LedgerSource
import com.investment.dashboard.ledger.manualDeposit
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

@Service
class ManualEntryService(
    private val accountRepository: AccountRepository,
    private val ledgerEntryRepository: LedgerEntryRepository,
    private val dividendProfileService: DividendProfileService,
    private val clock: Clock,
) {

    @Transactional
    fun addDividend(accountId: Long, request: ManualDividendRequest): Long {
        accountRepository.requireManualAccount(accountId)
        requireNotFuture(request.date)
        val entry = LedgerEntry(accountId, LedgerEntryType.DIVIDEND, KRW, request.date, LedgerSource.MANUAL)
        entry.symbol = request.symbol
        entry.amount = request.amount
        entry.netAmount = request.amount
        val id = checkNotNull(ledgerEntryRepository.save(entry).id)
        dividendProfileService.applyManualDividend(accountId, request.symbol, request.amount, request.date)
        return id
    }

    @Transactional
    fun addDeposit(accountId: Long, request: DepositRequest): Long {
        accountRepository.requireManualAccount(accountId)
        requireNotFuture(request.date)
        return checkNotNull(ledgerEntryRepository.save(manualDeposit(accountId, request.date, request.amount)).id)
    }

    @Transactional
    fun deleteEntry(id: Long) {
        val entry = ledgerEntryRepository.findByIdOrNull(id) ?: throw NotFoundException("원장 행 없음: $id")
        if (entry.source != LedgerSource.MANUAL) {
            throw ManualEntryNotAllowedException("수동 입력 원장 행만 삭제 가능: $id")
        }
        ledgerEntryRepository.delete(entry)
    }

    private fun requireNotFuture(date: LocalDate) {
        if (date.isAfter(LocalDate.now(clock))) {
            throw InvalidRequestException(mapOf("date" to "오늘 이후 날짜는 입력 불가"))
        }
    }
}

private const val KRW = "KRW"
