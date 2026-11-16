package com.glebskorobogatov.subscriptiontracker.ui.history

import com.glebskorobogatov.subscriptiontracker.data.integration.PaymentHistoryItem
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class HistoryTotalsTest {
    private fun item(currency: String,minor: Long) = historyPayment().let {
        PaymentHistoryItem(it.copy(amountMinor = minor,currencyCode = CurrencyCode.of(currency)),emptyList(),Money(minor,CurrencyCode.of(currency)))
    }
    @Test fun netAmountsAreGroupedByCurrencyInStableOrder() {
        val rows = listOf(item("USD",1000),item("RUB",49900),item("USD",500),item("JPY",1200),item("KWD",12345))
        assertEquals(listOf("1200 JPY","12,345 KWD","499,00 RUB","15,00 USD"),historyNetTotals(rows).map { "${it.format()} ${it.currency.value}" })
    }
    @Test fun summaryUsesExistingNetExpenseIncludingFullRefundZero() {
        val payment = item("RUB",99900)
        assertEquals(listOf(Money(49900,CurrencyCode.of("RUB"))),historyNetTotals(listOf(payment.copy(netExpense = Money(49900,payment.netExpense.currency)),
            payment.copy(netExpense = Money(0,payment.netExpense.currency)))))
    }
    @Test(expected = ArithmeticException::class) fun overflowingTotalNeverWrapsIntoFalseAmount() {
        historyNetTotals(listOf(item("JPY",Long.MAX_VALUE),item("JPY",1)))
    }
    @Test fun emptyHistoryHasNoInventedTotals() { assertTrue(historyNetTotals(emptyList()).isEmpty()) }
}
