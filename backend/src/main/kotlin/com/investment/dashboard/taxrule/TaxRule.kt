package com.investment.dashboard.taxrule

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(comment = "세법 값 (연도별 한도·세율)")
class TaxRule(
    @Column(comment = "적용 연도")
    var year: Int,

    @Column(comment = "항목 키")
    @Enumerated(EnumType.STRING)
    var ruleKey: TaxRuleKey,

    @Column(comment = "값 (금액은 원, 비율은 소수)")
    var value: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(comment = "식별자")
    var id: Long? = null
}
