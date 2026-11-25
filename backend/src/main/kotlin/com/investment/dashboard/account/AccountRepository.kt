package com.investment.dashboard.account

import org.springframework.data.jpa.repository.JpaRepository

interface AccountRepository : JpaRepository<Account, Long> {
    fun findByTossAccountSeq(seq: Long): Account?

    fun findByKind(kind: AccountKind): List<Account>
}
