package com.investment.dashboard.taxrule

import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class TaxRules(private val taxRuleRepository: TaxRuleRepository) {

    fun get(year: Int, key: TaxRuleKey): BigDecimal =
        taxRuleRepository.findByYearAndRuleKey(year, key)?.value ?: throw TaxRuleMissingException(year)
}
