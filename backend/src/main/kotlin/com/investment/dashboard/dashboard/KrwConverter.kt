package com.investment.dashboard.dashboard

import com.investment.dashboard.dividend.ForecastItem
import com.investment.dashboard.fx.FxRateRepository
import com.investment.dashboard.ledger.LedgerEntry
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class KrwConverter(private val fxRateRepository: FxRateRepository) {

    fun actual(dividend: LedgerEntry): KrwPair {
        val label = "${dividend.symbol} ${dividend.settlementDate}"
        val rate = when (dividend.currency) {
            KRW -> BigDecimal.ONE
            USD -> checkNotNull(dividend.exchangeRate) { "환율 없는 USD 배당: $label" }
            else -> error("원화로 바꿀 수 없는 배당 통화: ${dividend.currency} $label")
        }
        val gross = checkNotNull(dividend.amount) { "세전 금액 없는 배당: $label" }
        val net = checkNotNull(dividend.netAmount) { "세후 금액 없는 배당: $label" }
        return KrwPair(won(gross, rate), won(net, rate))
    }

    fun forecast(item: ForecastItem): KrwPair {
        val label = "${item.symbol} ${item.month}"
        val rate = when (item.currency) {
            KRW -> BigDecimal.ONE
            USD -> fxRateRepository.findByIdOrNull(USD)?.krwRate ?: error("USD 최신 환율 없음: $label")
            else -> error("원화로 바꿀 수 없는 예상 통화: ${item.currency} $label")
        }
        return KrwPair(won(item.gross, rate), won(item.net, rate))
    }
}

private fun won(amount: BigDecimal, krwRate: BigDecimal): Long =
    amount.multiply(krwRate).setScale(0, RoundingMode.HALF_UP).longValueExact()

private const val KRW = "KRW"
private const val USD = "USD"
