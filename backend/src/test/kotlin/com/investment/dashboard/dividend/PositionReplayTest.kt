package com.investment.dashboard.dividend

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PositionReplayTest {

    @Test
    @DisplayName("매수와 매도를 결제일 순서대로 더하고 빼서 그날 수량을 낸다")
    fun adds_buys_and_subtracts_sells_up_to_the_date() {
        val entries = listOf(
            buy("SCHD", "10", "2026-01-05"),
            buy("SCHD", "5", "2026-02-03"),
            sell("SCHD", "3", "2026-03-10"),
        )

        val beforeSell = PositionReplay.quantityAt(entries, "SCHD", LocalDate.parse("2026-02-28"))
        val onSellDate = PositionReplay.quantityAt(entries, "SCHD", LocalDate.parse("2026-03-10"))

        assertThat(beforeSell).isEqualByComparingTo("15")
        assertThat(onSellDate).isEqualByComparingTo("12")
    }

    @Test
    @DisplayName("취소 행은 수량 계산에서 무시한다")
    fun ignores_cancelled_rows() {
        val entries = listOf(
            buy("SCHD", "10", "2026-01-05"),
            buy("SCHD", "4", "2026-02-03").apply { isCancellation = true },
        )

        val quantity = PositionReplay.quantityAt(entries, "SCHD", LocalDate.parse("2026-03-01"))

        assertThat(quantity).isEqualByComparingTo("10")
    }

    @Test
    @DisplayName("이관 입고는 더하고 이관 출고는 뺀다")
    fun adds_transfer_in_and_subtracts_transfer_out() {
        val entries = listOf(
            transferIn("SCHD", "20", "2026-01-05"),
            transferOut("SCHD", "5", "2026-02-03"),
        )

        val quantity = PositionReplay.quantityAt(entries, "SCHD", LocalDate.parse("2026-03-01"))

        assertThat(quantity).isEqualByComparingTo("15")
    }

    @Test
    @DisplayName("다른 종목의 거래는 수량에 넣지 않는다")
    fun ignores_other_symbols() {
        val entries = listOf(
            buy("SCHD", "10", "2026-01-05"),
            buy("VTI", "7", "2026-01-06"),
        )

        val quantity = PositionReplay.quantityAt(entries, "SCHD", LocalDate.parse("2026-03-01"))

        assertThat(quantity).isEqualByComparingTo("10")
    }

    @Test
    @DisplayName("수량 없는 매수 행이 있으면 종목과 결제일을 담아 실패한다")
    fun trade_row_without_quantity_fails_with_symbol_and_date() {
        val entries = listOf(
            buy("SCHD", "10", "2026-01-05"),
            buy("SCHD", "4", "2026-02-03").apply { quantity = null },
        )

        assertThatThrownBy { PositionReplay.quantityAt(entries, "SCHD", LocalDate.parse("2026-03-01")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("수량 없는 BUY 행: SCHD 2026-02-03")
    }
}
