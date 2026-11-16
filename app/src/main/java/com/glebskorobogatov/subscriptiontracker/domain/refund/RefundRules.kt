package com.glebskorobogatov.subscriptiontracker.domain.refund

import com.glebskorobogatov.subscriptiontracker.domain.model.Money
import com.glebskorobogatov.subscriptiontracker.domain.payment.FuturePaymentRejected
import java.time.LocalDate

data class PaidAmount(val paidOn: LocalDate, val amount: Money)
data class ReturnedAmount(val refundedOn: LocalDate, val amount: Money)
class HistoryConflict(message: String) : IllegalStateException(message)

/** Single source of validation for add/edit and net expenses. No calendar or subscription dependency. */
class RefundRules {
    fun netExpense(payment: PaidAmount?, refunds: List<ReturnedAmount>, today: LocalDate): Money {
        val paid = payment ?: throw HistoryConflict("Исходный платёж не найден")
        paid.amount.requirePositive()
        if (paid.paidOn > today) throw FuturePaymentRejected()
        val total = refunds.fold(Money(0, paid.amount.currency)) { sum, refund ->
            refund.amount.requirePositive()
            require(refund.amount.currency == paid.amount.currency) { "Валюта возврата должна совпадать с платежом" }
            require(refund.refundedOn >= paid.paidOn && refund.refundedOn <= today) { "Дата возврата должна быть между датой оплаты и today" }
            sum + refund.amount // Math.addExact, never silently wraps Long.
        }
        require(total.amountMinor <= paid.amount.amountMinor) { "Сумма возвратов превышает платёж" }
        return paid.amount - total
    }
}
