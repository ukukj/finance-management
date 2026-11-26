package com.investment.dashboard.ledger

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.LocalDate

@Entity
@Table(comment = "거래 원장")
class LedgerEntry(
    @Column(comment = "계좌")
    var accountId: Long,

    @Column(comment = "거래 유형 (토스 transactionType, 종목 입금은 DIVIDEND)")
    @Enumerated(EnumType.STRING)
    var type: LedgerEntryType,

    @Column(comment = "통화 (KRW, USD)")
    var currency: String,

    @Column(comment = "결제일 (KST)")
    var settlementDate: LocalDate,

    @Column(comment = "출처 (TOSS 동기화, MANUAL 수동 입력)")
    @Enumerated(EnumType.STRING)
    var source: LedgerSource,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(comment = "식별자")
    var id: Long? = null

    @Column(comment = "종목 코드")
    var symbol: String? = null

    @Column(comment = "수량 (매매·입출고만)")
    var quantity: BigDecimal? = null

    @Column(comment = "거래금액 (종목 통화, 세전)")
    var amount: BigDecimal? = null

    @Column(comment = "세금 (종목 통화)")
    var tax: BigDecimal = BigDecimal.ZERO

    @Column(comment = "정산 금액 (종목 통화, 세후)")
    var netAmount: BigDecimal? = null

    @Column(comment = "원화 환율 (USD 결제 완료분)")
    var exchangeRate: BigDecimal? = null

    @Column(comment = "취소 거래 여부")
    var isCancellation: Boolean = false

    @Column(comment = "토스 응답 원본")
    @JdbcTypeCode(SqlTypes.JSON)
    var raw: String? = null

    @Column(comment = "메모")
    var memo: String? = null
}

fun LedgerEntry.isValidDividend(today: LocalDate): Boolean {
    val gross = amount
    return type == LedgerEntryType.DIVIDEND && !isCancellation && !settlementDate.isAfter(today) &&
        gross != null && gross.signum() > 0 && netAmount != null
}
