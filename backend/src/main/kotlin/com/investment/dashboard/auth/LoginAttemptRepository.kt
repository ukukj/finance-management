package com.investment.dashboard.auth

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface LoginAttemptRepository : JpaRepository<LoginAttempt, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(id: Short): LoginAttempt?
}
