package com.glebskorobogatov.subscriptiontracker.domain.refund

import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.payment.FuturePaymentRejected
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RefundRulesTest {
    private val day = LocalDate.of(2027, 11, 10)
    private val rub = CurrencyCode.of("RUB")
    private val p = PaidAmount(day.minusDays(1), Money(999, rub))
    private val rules = RefundRules()
    private fun r(n: Long) = ReturnedAmount(day, Money(n, rub))
    @Test fun moneyT08PartialReturnsLeave499() {
        assertEquals(Money(499, rub), rules.netExpense(p, listOf(r(300), r(200)), day))
    }
    @Test fun moneyT09FullReturnLeavesZero() {
        assertEquals(Money(0, rub), rules.netExpense(p, listOf(r(300), r(200), r(499)), day))
    }
    @Test fun moneyT10ExtraUnitRejected() {
        assertThrows(IllegalArgumentException::class.java) { rules.netExpense(p, listOf(r(999), r(1)), day) }
    }
    @Test fun moneyT11PaymentReductionBelowRefundsRejected() {
        assertThrows(IllegalArgumentException::class.java) { rules.netExpense(p.copy(amount = Money(499, rub)), listOf(r(300), r(200)), day) }
        assertEquals(Money(0, rub), rules.netExpense(p.copy(amount = Money(500, rub)), listOf(r(300), r(200)), day))
    }
    @Test fun moneyT12CurrencyMustMatchPayment() {
        assertThrows(IllegalArgumentException::class.java) { rules.netExpense(p, listOf(ReturnedAmount(day, Money(1, CurrencyCode.of("USD")))), day) }
    }
    @Test fun moneyT13EditedOrRemovedRefundChangesOnlyNet() {
        assertEquals(Money(399, rub), rules.netExpense(p, listOf(r(400), r(200)), day))
        assertEquals(Money(799, rub), rules.netExpense(p, listOf(r(200)), day))
        assertEquals(999L, p.amount.amountMinor)
    }
    @Test fun missingPaymentAndNonpositiveAmountsRejected() {
        assertThrows(HistoryConflict::class.java) { rules.netExpense(null, listOf(r(1)), day) }
        assertThrows(IllegalArgumentException::class.java) { rules.netExpense(p, listOf(r(0)), day) }
        assertThrows(IllegalArgumentException::class.java) { Money(-1, rub) }
        assertThrows(IllegalArgumentException::class.java) { rules.netExpense(p.copy(amount = Money(0, rub)), emptyList(), day) }
    }
    @Test fun checkedLongOverflowAndMaximumBoundary() {
        val max = p.copy(amount = Money(Long.MAX_VALUE, rub))
        assertEquals(Money(0, rub), rules.netExpense(max, listOf(r(Long.MAX_VALUE - 1), r(1)), day))
        assertThrows(ArithmeticException::class.java) { rules.netExpense(max, listOf(r(Long.MAX_VALUE), r(1)), day) }
    }
    @Test fun moneyT17DateBoundariesAndPaymentEditDate() {
        assertEquals(Money(997, rub), rules.netExpense(p, listOf(r(1).copy(refundedOn = p.paidOn), r(1)), day))
        for (date in listOf(p.paidOn.minusDays(1), day.plusDays(1))) {
            assertThrows(IllegalArgumentException::class.java) { rules.netExpense(p, listOf(r(1).copy(refundedOn = date)), day) }
        }
        assertThrows(FuturePaymentRejected::class.java) { rules.netExpense(p.copy(paidOn = day.plusDays(1)), emptyList(), day) }
        assertThrows(IllegalArgumentException::class.java) { rules.netExpense(p.copy(paidOn = day), listOf(r(1).copy(refundedOn = day.minusDays(1))), day) }
    }
}
