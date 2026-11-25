package com.investment.dashboard.fx

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime

@Entity
@Table(comment = "최신 환율")
class FxRate(
    @Id
    @Column(comment = "통화")
    var currency: String,

    @Column(comment = "1단위당 원화 (토스 참고 환율)")
    var krwRate: BigDecimal,

    @Column(comment = "조회 시각")
    var asOf: OffsetDateTime,
)
