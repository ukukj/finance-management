package com.investment.dashboard.account

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate

@Entity
@Table(comment = "증권 계좌")
class Account(
    @Column(comment = "계좌 종류 (BROKERAGE 일반, ISA, PENSION_SAVINGS 연금저축)")
    @Enumerated(EnumType.STRING)
    var kind: AccountKind,

    @Column(comment = "증권사 (TOSS 토스증권, MIRAE 미래에셋증권)")
    @Enumerated(EnumType.STRING)
    var broker: Broker,

    @Column(comment = "표시 이름")
    var name: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(comment = "식별자")
    var id: Long? = null

    @Column(comment = "토스 계좌 식별 키 (토스 계좌만)")
    var tossAccountSeq: Long? = null

    @Column(comment = "ISA 유형 (GENERAL 일반형, LOW_INCOME 서민형)")
    @Enumerated(EnumType.STRING)
    var isaType: IsaType? = null

    @Column(comment = "ISA 가입일")
    var isaOpenDate: LocalDate? = null

    @Column(comment = "ISA 계약 만기일")
    var isaMaturityDate: LocalDate? = null

    @Column(comment = "앱 기록 이전 누적 납입액 (원, ISA 이월 한도 계산용)")
    var priorContributionTotal: BigDecimal = BigDecimal.ZERO
}
