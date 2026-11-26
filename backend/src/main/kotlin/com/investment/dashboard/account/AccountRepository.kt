package com.investment.dashboard.account

import com.investment.dashboard.common.ManualEntryNotAllowedException
import com.investment.dashboard.common.NotFoundException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull

interface AccountRepository : JpaRepository<Account, Long> {
    fun findByTossAccountSeq(seq: Long): Account?

    fun findByKind(kind: AccountKind): List<Account>
}

fun AccountRepository.requireAccount(id: Long): Account = findByIdOrNull(id) ?: throw NotFoundException("계좌 없음: $id")

fun AccountRepository.requireManualAccount(id: Long): Account {
    val account = requireAccount(id)
    if (account.broker != Broker.MIRAE) {
        throw ManualEntryNotAllowedException("미래에셋 계좌만 수동 입력 가능: $id")
    }
    return account
}
