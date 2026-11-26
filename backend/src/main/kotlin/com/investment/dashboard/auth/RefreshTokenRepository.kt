package com.investment.dashboard.auth

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByTokenHash(tokenHash: String): RefreshToken?

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < ?1")
    fun deleteExpired(now: OffsetDateTime): Int
}
