package com.glebskorobogatov.subscriptiontracker.data.payment

import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.payment.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun SubscriptionEntity.toDomain() = Subscription(SubscriptionId(id), renewalMode,
    RenewalSchedule(RenewalAnchor(YearMonth.of(anchorYear, anchorMonth), anchorDay), Frequency(intervalUnit, intervalCount),
        nextCycleIndex, oneOffDueDate?.let { CycleOverride(requireNotNull(overrideCycleIndex), it) }),
    Money(priceMinor, currencyCode), lifecycleState, paidAccessEndDate, currentPeriodStartOverride)

/** Preserve storage-only metadata while persisting every lifecycle/schedule field. */
fun SubscriptionEntity.withDomain(s: Subscription, changedAt: Instant): SubscriptionEntity {
    require(id == s.id.value)
    return copy(renewalMode = s.mode, lifecycleState = s.state,
        priceMinor = s.price.amountMinor, currencyCode = s.price.currency,
        intervalUnit = s.schedule.frequency.unit, intervalCount = s.schedule.frequency.count,
        anchorYear = s.schedule.anchor.yearMonth.year, anchorMonth = s.schedule.anchor.yearMonth.monthValue,
        anchorDay = s.schedule.anchor.day, nextCycleIndex = s.schedule.nextCycleIndex,
        oneOffDueDate = s.schedule.oneOff?.date, overrideCycleIndex = s.schedule.oneOff?.cycleIndex,
        currentPeriodStartOverride = s.periodStartOverride, paidAccessEndDate = s.paidAccessEnd, updatedAt = changedAt)
}

/** The entire synchronous Room transaction runs on IO; no suspensions inside its transaction block. */
class PaymentRepository(private val database: TrackerDatabase, private val rules: PaymentRules = PaymentRules()) {
    suspend fun execute(command: PaymentCommand, today: LocalDate, recordedAt: Instant,
        expected: SubscriptionEntity? = null): PaymentEntity = withContext(Dispatchers.IO) {
        database.runInTransaction(Callable {
            if (database.retiredPaymentOperations().contains(command.operationId.value))
                throw PaymentConflict("Операция относится к удалённому платежу; повторное выполнение запрещено")
            val payload = command.payload()
            val existing = database.payments().findByOperation(command.operationId.value)
            if (existing != null) {
                if (existing.requestPayload != payload) throw PaymentConflict("operationId уже использован с другими или неизвестными параметрами")
                return@Callable existing
            }
            val stored = database.subscriptions().find(command.subscriptionId.value)
                ?: throw PaymentConflict("Подписка не найдена")
            // Optional full snapshot for integrated UI commands; receipt replay stays above this check.
            if (expected != null && stored != expected) throw PaymentConflict("Подписка изменена; обновите данные перед подтверждением")
            val result = rules.apply(stored.toDomain(), command, today)
            val intent = requireNotNull(result.paymentIntent)
            val payment = PaymentEntity(UUID.randomUUID(), stored.id, command.operationId.value, intent.paidOn,
                intent.amount.amountMinor, intent.amount.currency, recordedAt, intent.source, payload)
            database.payments().insert(payment)
            if (command.source == PaymentSource.CONFIRMED_RENEWAL) {
                val s = result.subscription
                check(database.subscriptions().update(stored.withDomain(s, recordedAt)) == 1)
            }
            payment
        })
    }
}
