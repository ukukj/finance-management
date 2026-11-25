package com.investment.dashboard.holding

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime

@Entity
@Table(comment = "계좌별 보유 종목")
class Holding(
    @Column(comment = "계좌")
    var accountId: Long,

    @Column(comment = "종목 코드 (국내 6자리, 미국 티커)")
    var symbol: String,

    @Column(comment = "종목명")
    var name: String,

    @Column(comment = "시장 (KR, US)")
    var marketCountry: String,

    @Column(comment = "통화 (KRW, USD)")
    var currency: String,

    @Column(comment = "보유 수량")
    var quantity: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(comment = "식별자")
    var id: Long? = null

    @Column(comment = "평균 매수가 (종목 통화)")
    var averagePrice: BigDecimal? = null

    @Column(comment = "갱신 시각")
    lateinit var updatedAt: OffsetDateTime
}
