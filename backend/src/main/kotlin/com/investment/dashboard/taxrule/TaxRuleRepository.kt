package com.investment.dashboard.taxrule

import org.springframework.data.jpa.repository.JpaRepository

interface TaxRuleRepository : JpaRepository<TaxRule, Long> {
    fun findByYearAndRuleKey(year: Int, ruleKey: TaxRuleKey): TaxRule?
}
