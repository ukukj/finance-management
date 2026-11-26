package com.investment.dashboard.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(comment = "로그인 실패 잠금 상태")
class LoginAttempt(
    @Id
    @Column(comment = "식별자 (항상 1)")
    var id: Short,

    @Column(comment = "연속 실패 횟수")
    var failedCount: Int,

    @Column(comment = "잠금 해제 시각")
    var lockedUntil: OffsetDateTime?,
)
