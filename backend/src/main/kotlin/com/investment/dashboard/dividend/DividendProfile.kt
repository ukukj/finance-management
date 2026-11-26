package com.investment.dashboard.dividend

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.SortedSet

@Entity
@Table(comment = "종목별 배당 정보")
class DividendProfile(
    @Column(comment = "계좌")
    var accountId: Long,

    @Column(comment = "종목 코드")
    var symbol: String,

    @Column(comment = "주당 배당 (종목 통화, 세전)")
    var perShare: BigDecimal,

    @Column(comment = "통화 (KRW, USD)")
    var currency: String,

    @Column(comment = "지급 월 목록 (쉼표 구분, 예 3,6,9,12)")
    var payMonths: String,

    @Column(comment = "세후/세전 비율 (수동 입력은 1)")
    var netRatio: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(comment = "식별자")
    var id: Long? = null

    @Column(comment = "출처 (AUTO 이력 추정, MANUAL 수동 입력)")
    @Enumerated(EnumType.STRING)
    lateinit var source: ProfileSource

    @Column(comment = "갱신 시각")
    lateinit var updatedAt: OffsetDateTime

    fun payMonthSet(): SortedSet<Int> = payMonths.split(",").map { it.toInt() }.toSortedSet()
}
