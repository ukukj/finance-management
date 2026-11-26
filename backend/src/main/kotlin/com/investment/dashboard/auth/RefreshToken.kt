package com.investment.dashboard.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(comment = "로그인 유지 토큰")
class RefreshToken(
    @Column(comment = "토큰 해시 (HMAC-SHA256 16진수)")
    var tokenHash: String,

    @Column(comment = "만료 시각")
    var expiresAt: OffsetDateTime,

    @Column(comment = "발급 시각")
    var createdAt: OffsetDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(comment = "식별자")
    var id: Long? = null
}
