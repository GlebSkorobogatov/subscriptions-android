package com.glebskorobogatov.subscriptiontracker.domain.payment

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.LocalDate

class PaymentConflict(message: String) : IllegalArgumentException(message)
class FuturePaymentRejected : IllegalArgumentException("Фактическая дата оплаты не может быть позже today")

data class PaymentCommand(
    val operationId: OperationId, val subscriptionId: SubscriptionId, val paidOn: LocalDate,
    val amount: Money, val source: PaymentSource,
    val updateFuturePrice: Boolean = false,
    val expectedSchedule: RenewalSchedule? = null, val expectedMode: RenewalMode? = null,
    val selectedNext: LocalDate? = null,
    val expectedState: LifecycleState = LifecycleState.ACTIVE,
    val reanchor: Boolean = false,
) {
    /** Versioned canonical fields, no locale, clock or generated payment ID. */
    fun payload(): String {
        val s = expectedSchedule
        val base = listOf("v1", subscriptionId, paidOn, amount.amountMinor, amount.currency.value, source,
            updateFuturePrice, expectedMode, selectedNext, s?.anchor?.yearMonth, s?.anchor?.day,
            s?.frequency?.unit, s?.frequency?.count, s?.nextCycleIndex,
            s?.oneOff?.cycleIndex, s?.oneOff?.date).joinToString("|") { it?.toString() ?: "~" } +
            if (expectedState == LifecycleState.ACTIVE) "" else "|state=$expectedState"
        return base + if (reanchor) "|reanchor=true" else ""
    }
}

class PaymentRules(private val lifecycle: SubscriptionLifecycleService = SubscriptionLifecycleService()) {
    fun apply(s: Subscription, command: PaymentCommand, today: LocalDate): LifecycleResult {
        require(s.id == command.subscriptionId)
        command.amount.requirePositive()
        if (command.paidOn > today) throw FuturePaymentRejected()
        if (command.source == PaymentSource.MANUAL_HISTORY) {
            require(command.expectedState == LifecycleState.ACTIVE)
            require(!command.reanchor && !command.updateFuturePrice && command.expectedSchedule == null && command.expectedMode == null && command.selectedNext == null)
            return lifecycle.addHistoricalPayment(s, command.operationId, command.paidOn, command.amount)
        }
        if (s.state != command.expectedState) throw InvalidTransition("Состояние изменилось; обновите данные")
        if (s.schedule != command.expectedSchedule || s.mode != command.expectedMode) throw PaymentConflict("График изменился; обновите данные")
        require(command.amount.currency == s.price.currency) { "Валюта подтверждения не совпадает с валютой подписки" }
        val result = if (command.expectedState == LifecycleState.PAYMENT_ISSUE) {
            lifecycle.resolveWithPayment(s, command.operationId, command.paidOn, command.amount,
                requireNotNull(command.selectedNext) { "Выберите следующую дату" },command.reanchor)
        } else if (s.mode == RenewalMode.MANUAL) lifecycle.confirmRenewal(s, command.operationId,
            command.paidOn, command.amount, requireNotNull(command.selectedNext) { "Выберите следующую дату" },command.reanchor)
        else {
            require(command.selectedNext == null && !command.reanchor) { "AUTO сохраняет регулярный график" }
            lifecycle.confirmAuto(s, command.operationId, command.paidOn, command.amount)
        }
        return if (command.updateFuturePrice) result.copy(subscription = result.subscription.copy(price = command.amount)) else result
    }
}
