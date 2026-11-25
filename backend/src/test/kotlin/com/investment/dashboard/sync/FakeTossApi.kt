package com.investment.dashboard.sync

import com.investment.dashboard.toss.TossAccount
import com.investment.dashboard.toss.TossApi
import com.investment.dashboard.toss.TossHolding
import com.investment.dashboard.toss.TossTransaction
import java.math.BigDecimal

class FakeTossApi : TossApi {

    var calls = 0
        private set

    private var accounts = emptyList<TossAccount>()
    private var holdings = emptyList<TossHolding>()
    private var transactions = emptyList<TossTransaction>()
    private var usdKrwRate = BigDecimal.ZERO
    private var accountsFailure: RuntimeException? = null
    private var transactionsFailure: RuntimeException? = null
    private var onCall: () -> Unit = {}

    init {
        reset()
    }

    fun reset() {
        calls = 0
        accounts = listOf(TossAccount("00000000000", 1L, "BROKERAGE"))
        holdings = emptyList()
        transactions = emptyList()
        usdKrwRate = BigDecimal("1300")
        accountsFailure = null
        transactionsFailure = null
        onCall = {}
    }

    fun givenAccounts(accounts: List<TossAccount>) {
        this.accounts = accounts
    }

    fun givenHoldings(holdings: List<TossHolding>) {
        this.holdings = holdings
    }

    fun givenTransactions(transactions: List<TossTransaction>) {
        this.transactions = transactions
    }

    fun givenUsdKrwRate(rate: BigDecimal) {
        usdKrwRate = rate
    }

    fun failOnAccounts(error: RuntimeException) {
        accountsFailure = error
    }

    fun failOnTransactions(error: RuntimeException) {
        transactionsFailure = error
    }

    fun onEachCall(action: () -> Unit) {
        onCall = action
    }

    override fun accounts(): List<TossAccount> {
        calls++
        onCall()
        accountsFailure?.let { throw it }
        return accounts
    }

    override fun holdings(accountSeq: Long): List<TossHolding> {
        calls++
        onCall()
        return holdings
    }

    override fun allTransactions(accountSeq: Long): List<TossTransaction> {
        calls++
        onCall()
        transactionsFailure?.let { throw it }
        return transactions
    }

    override fun usdKrwRate(): BigDecimal {
        calls++
        onCall()
        return usdKrwRate
    }
}
