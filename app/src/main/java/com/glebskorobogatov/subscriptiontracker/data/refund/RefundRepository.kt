package com.glebskorobogatov.subscriptiontracker.data.refund

import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.model.Money
import com.glebskorobogatov.subscriptiontracker.domain.refund.*
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All reads, validations and writes run in one synchronous Room transaction on IO. */
class RefundRepository(private val database: TrackerDatabase, private val rules: RefundRules = RefundRules()) {
    private suspend fun <T> transaction(block: () -> T): T = withContext(Dispatchers.IO) {
        database.runInTransaction(Callable { block() })
    }
    private fun payment(id: UUID): PaymentEntity = database.payments().find(id)
        ?: throw HistoryConflict("Исходный платёж не найден")
    private fun checkPayment(expected: PaymentEntity): PaymentEntity = payment(expected.id).also {
        if (it != expected) throw HistoryConflict("Платёж изменён; обновите данные")
    }
    private fun checkRefund(expected: RefundEntity): RefundEntity =
        (database.refunds().find(expected.id) ?: throw HistoryConflict("Возврат не найден")).also {
            if (it != expected) throw HistoryConflict("Возврат изменён; обновите данные")
        }
    private fun net(p: PaymentEntity, refunds: List<RefundEntity>, today: LocalDate): Money {
        require(refunds.all { it.paymentId == p.id })
        return rules.netExpense(PaidAmount(p.paidOn, Money(p.amountMinor, p.currencyCode)),
            refunds.map { ReturnedAmount(it.refundedOn, Money(it.amountMinor, it.currencyCode)) }, today)
    }
    suspend fun netExpense(paymentId: UUID, today: LocalDate): Money = transaction {
        net(payment(paymentId), database.refunds().forPayment(paymentId), today)
    }
    suspend fun addRefund(refund: RefundEntity, today: LocalDate): RefundEntity = transaction {
        if (database.retiredPaymentOperations().containsRefund(refund.id))
            throw HistoryConflict("Возврат был удалён; повторное добавление с этим UUID запрещено")
        val existing = database.refunds().find(refund.id)
        if (existing != null) {
            if (existing != refund) throw HistoryConflict("UUID возврата уже использован с другими параметрами")
            existing
        } else {
            net(payment(refund.paymentId), database.refunds().forPayment(refund.paymentId) + refund, today)
            database.refunds().insert(refund)
            refund
        }
    }
    suspend fun editRefund(expected: RefundEntity, refundedOn: LocalDate, amount: Money, today: LocalDate): RefundEntity = transaction {
        checkRefund(expected)
        val updated = expected.copy(refundedOn = refundedOn, amountMinor = amount.amountMinor, currencyCode = amount.currency)
        val others = database.refunds().forPayment(expected.paymentId).filterNot { it.id == expected.id }
        net(payment(expected.paymentId), others + updated, today)
        check(database.refunds().update(updated) == 1)
        updated
    }
    suspend fun deleteRefund(expected: RefundEntity, today: LocalDate): Money = transaction {
        checkRefund(expected)
        val remaining = database.refunds().forPayment(expected.paymentId).filterNot { it.id == expected.id }
        val result = net(payment(expected.paymentId), remaining, today)
        database.retiredPaymentOperations().retireRefund(RetiredRefundId(expected.id))
        check(database.refunds().delete(expected.id) == 1)
        result
    }
    suspend fun editPayment(expected: PaymentEntity, paidOn: LocalDate, amount: Money, today: LocalDate): PaymentEntity = transaction {
        checkPayment(expected)
        val updated = expected.copy(paidOn = paidOn, amountMinor = amount.amountMinor, currencyCode = amount.currency)
        net(updated, database.refunds().forPayment(expected.id), today)
        check(database.payments().update(updated) == 1)
        updated // id, operationId, source, recordedAt and original requestPayload are preserved.
    }
    suspend fun deletePayment(expected: PaymentEntity, confirmed: Boolean) = transaction {
        require(confirmed) { "Удаление платежа и возвратов требует отдельного подтверждения" }
        checkPayment(expected)
        database.retiredPaymentOperations().insert(RetiredPaymentOperation(expected.operationId))
        check(database.payments().delete(expected.id) == 1) // existing FK deletes refunds atomically
    }
}
