package com.glebskorobogatov.subscriptiontracker.data.integration

import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.payment.*
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.refund.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.payment.*
import com.glebskorobogatov.subscriptiontracker.domain.refund.*
import com.glebskorobogatov.subscriptiontracker.domain.forecast.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*

data class PaymentHistoryItem(val payment: PaymentEntity, val refunds: List<RefundEntity>, val netExpense: Money)
data class SubscriptionDetails(val subscription: SubscriptionEntity, val lifecycle: LifecycleView,
    val history: List<PaymentHistoryItem>, val reminders: List<ReminderOverrideEntity>)

/** No owned scope/cache. Caller owns DB and Settings lease; UI may stateIn its own lifecycle scope. */
class SubscriptionUseCases(
    private val database: TrackerDatabase,
    private val settingsRepository: SettingsRepository,
    val date: CurrentDate,
    private val payments: PaymentRepository = PaymentRepository(database),
    private val lifecycle: LifecycleRepository = LifecycleRepository(database),
    private val refunds: RefundRepository = RefundRepository(database),
    private val reminderChanges: com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderChanges = com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderChanges(),
    private val assetStore: com.glebskorobogatov.subscriptiontracker.data.assets.AssetStore? = null,
) {
    private val editor = SubscriptionEditor(database)
    private val source = RoomLocalDataSource(database, settingsRepository)
    private val forecast = ForecastEngine()
    val subscriptions = source.subscriptions.distinctUntilChanged()
    val settings = source.settings
    private val domainSubscriptions = subscriptions.map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()
    val groups = combine(domainSubscriptions, date.today, ::groupSubscriptions).distinctUntilChanged().flowOn(Dispatchers.Default)
    val active = groups.map { it.active }.distinctUntilChanged()
    val needsDecision = groups.map { it.needsDecision }.distinctUntilChanged()
    val archive = groups.map { it.archived }.distinctUntilChanged()
    val forecast30Days = combine(domainSubscriptions, date.today) { rows, today -> forecast.next30Days(rows, today) }
        .distinctUntilChanged().flowOn(Dispatchers.Default)
    fun forecastMonths(months: Int, currency: CurrencyCode? = null): Flow<ForecastResult> {
        require(months in setOf(3, 6, 12))
        return combine(domainSubscriptions, date.today) { rows, today -> forecast.calendarMonths(rows, today, months, currency) }
            .distinctUntilChanged().flowOn(Dispatchers.Default)
    }
    val selectedChart = combine(domainSubscriptions, date.today,
        settings.map { it.chartHorizonMonths to it.selectedChartCurrency }.distinctUntilChanged()) { rows, today, selection ->
        forecast.calendarMonths(rows, today, selection.first, selection.second)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun details(id: UUID): Flow<SubscriptionDetails?> = combine(database.subscriptions().observeHistory(id), date.today) { row, today ->
        row?.let {
            val history = it.payments.sortedWith(compareBy<PaymentWithRefunds> { item -> item.payment.paidOn }.thenBy { item -> item.payment.id.toString() }).map { item ->
                val p = item.payment
                val returns = item.refunds.sortedWith(compareBy<RefundEntity> { r -> r.refundedOn }.thenBy { r -> r.id.toString() })
                PaymentHistoryItem(p, returns, RefundRules().netExpense(PaidAmount(p.paidOn, Money(p.amountMinor, p.currencyCode)),
                    returns.map { r -> ReturnedAmount(r.refundedOn, Money(r.amountMinor, r.currencyCode)) }, today))
            }
            SubscriptionDetails(it.subscription, SubscriptionLifecycleService().view(it.subscription.toDomain(), today), history,
                it.reminders.sortedBy { r -> r.eventType.name })
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun stageImage(uri: android.net.Uri): UUID = requireNotNull(assetStore).stage(uri)
    suspend fun discardImage(id: UUID) = withContext(Dispatchers.IO) {
        if (database.assets().find(id) == null) assetStore?.abort(id) else assetStore?.finish(id)
    }
    suspend fun assetFile(id: UUID): java.io.File? = withContext(Dispatchers.IO) {
        database.assets().find(id)?.let { assetStore?.readable(it) }
    }
    suspend fun pendingImage(id: UUID): java.io.File? = withContext(Dispatchers.IO) { assetStore?.pending(id) }
    suspend fun createSubscription(draft: SubscriptionDraft, recordedAt: Instant) = reminderChanges.write {
        val image = draft.logoAssetId?.let { assetStore?.prepared(it) }
        try {
            editor.create(draft, recordedAt, image).also { image?.let { assetStore?.finish(it.id) } }
        } catch (error: Exception) {
            image?.let { if (database.assets().find(it.id) == null) assetStore?.removeFile(it) }
            throw error
        }
    }
    suspend fun editSubscription(expected: SubscriptionEntity, edit: SubscriptionEdit, changedAt: Instant) = reminderChanges.write {
        val prepared = (edit.image as? ImageEdit.Set)?.let { requireNotNull(assetStore).prepared(it.assetId) }
        try {
            editor.edit(expected, edit, changedAt, prepared).also {
                prepared?.let { assetStore?.finish(it.id) }
                if (expected.logoAssetId != it.logoAssetId) expected.logoAssetId?.let { id -> runCatching { cleanupAsset(id) } }
            }
        } catch (error: Exception) {
            prepared?.let { if (database.assets().find(it.id) == null) assetStore?.removeFile(it) }
            throw error
        }
    }
    private suspend fun cleanupAsset(id: UUID) = withContext(Dispatchers.IO) {
        val orphan = database.runInTransaction(java.util.concurrent.Callable {
            database.assets().find(id)?.takeIf { database.assets().references(id) == 0 }?.also { database.assets().delete(id) }
        })
        orphan?.let { assetStore?.removeFile(it) }
    }
    suspend fun confirmPayment(expected: SubscriptionEntity, command: PaymentCommand, recordedAt: Instant): PaymentEntity {
        require(command.source == PaymentSource.CONFIRMED_RENEWAL && command.subscriptionId.value == expected.id)
        return reminderChanges.write { payments.execute(command, date.today.value, recordedAt, expected) }
    }
    suspend fun addHistoricalPayment(operationId: OperationId, subscriptionId: SubscriptionId, paidOn: LocalDate, amount: Money, recordedAt: Instant) =
        payments.execute(PaymentCommand(operationId, subscriptionId, paidOn, amount, PaymentSource.MANUAL_HISTORY), date.today.value, recordedAt)
    /** Covers MarkPaymentIssue, ResolveWithDate, DisableRenewal, Archive and Reactivate. */
    suspend fun changeLifecycle(expected: SubscriptionEntity, command: LifecycleCommand, changedAt: Instant) = reminderChanges.write { lifecycle.execute(expected, command, changedAt) }
    suspend fun resolvePaymentIssue(expected: SubscriptionEntity, command: PaymentCommand, recordedAt: Instant): PaymentEntity {
        require(command.expectedState == LifecycleState.PAYMENT_ISSUE)
        return confirmPayment(expected, command, recordedAt)
    }
    fun suggestNextDate(snapshot: SubscriptionEntity, paidOn: LocalDate): LocalDate = SubscriptionLifecycleService().suggestNextDate(snapshot.toDomain(), paidOn)
    suspend fun deleteSubscription(expected: SubscriptionEntity, confirmed: Boolean) = reminderChanges.write {
        lifecycle.delete(expected, confirmed)
        expected.logoAssetId?.let { runCatching { cleanupAsset(it) } }
    }
    suspend fun addRefund(value: RefundEntity) = refunds.addRefund(value, date.today.value)
    suspend fun editRefund(expected: RefundEntity, refundedOn: LocalDate, amount: Money) = refunds.editRefund(expected, refundedOn, amount, date.today.value)
    suspend fun deleteRefund(expected: RefundEntity) = refunds.deleteRefund(expected, date.today.value)
    suspend fun editPayment(expected: PaymentEntity, paidOn: LocalDate, amount: Money) = refunds.editPayment(expected, paidOn, amount, date.today.value)
    suspend fun deletePayment(expected: PaymentEntity, confirmed: Boolean) = refunds.deletePayment(expected, confirmed)
    suspend fun updateSettings(transform: (Settings) -> Settings) {
        reminderChanges.write(shouldNotify = { it }) {
            val before = settingsRepository.settings.first()
            settingsRepository.update(transform)
            val after = settingsRepository.settings.first()
            before.reminderLocalTime != after.reminderLocalTime ||
                before.paymentOffsetsDefault != after.paymentOffsetsDefault ||
                before.cancellationOffsetsDefault != after.cancellationOffsetsDefault
        }
    }
    /** Invoke on app return, not a timer. Explicit paid-access check runs before publishing the new day. */
    suspend fun refreshToday(today: LocalDate, changedAt: Instant): Int {
        val count = reminderChanges.write { lifecycle.autoArchiveExpired(today, changedAt) }
        date.update(today)
        return count
    }
}
