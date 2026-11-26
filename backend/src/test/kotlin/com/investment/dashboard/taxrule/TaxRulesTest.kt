package com.investment.dashboard.taxrule

import com.investment.dashboard.TestcontainersConfiguration
import com.investment.dashboard.support.TestClockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
class TaxRulesTest {

    @Autowired
    lateinit var taxRules: TaxRules

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(
        "PENSION_SAVINGS_CREDIT_LIMIT, 6000000",
        "PENSION_IRP_CREDIT_LIMIT, 9000000",
        "PENSION_CONTRIBUTION_LIMIT, 18000000",
        "CREDIT_RATE_LOW_INCOME, 0.165",
        "CREDIT_RATE_HIGH_INCOME, 0.132",
        "CREDIT_RATE_SALARY_THRESHOLD, 55000000",
        "ISA_ANNUAL_LIMIT, 20000000",
        "ISA_TOTAL_LIMIT, 100000000",
        "ISA_TAX_FREE_GENERAL, 2000000",
        "ISA_TAX_FREE_LOW_INCOME, 4000000",
        "ISA_EXCESS_TAX_RATE, 0.099",
        "ISA_PENSION_TRANSFER_DAYS, 60",
        "ISA_PENSION_TRANSFER_CREDIT_RATE, 0.10",
        "ISA_PENSION_TRANSFER_CREDIT_MAX, 3000000",
        "FINANCIAL_INCOME_THRESHOLD, 20000000",
    )
    @DisplayName("2026년 시드 세법 값을 키로 읽는다")
    fun seeded_2026_value_is_readable(key: TaxRuleKey, expected: String) {
        val value = taxRules.get(2026, key)

        assertThat(value).isEqualByComparingTo(expected)
    }

    @Test
    @DisplayName("세법 값이 없는 연도를 읽으면 그 연도를 담은 TaxRuleMissingException 이 난다")
    fun missing_year_throws_tax_rule_missing_exception_with_year() {
        assertThatThrownBy { taxRules.get(2027, TaxRuleKey.ISA_ANNUAL_LIMIT) }
            .isInstanceOfSatisfying(TaxRuleMissingException::class.java) { assertThat(it.year).isEqualTo(2027) }
            .hasMessage("2027년 세법 값 없음")
    }
}
