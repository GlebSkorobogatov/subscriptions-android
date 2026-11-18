package com.glebskorobogatov.subscriptiontracker.data.integration

import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.*
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.forecast.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*

/** A computation error belongs to these successfully read rows/today, never to storage readiness. */
sealed interface ForecastState {
    data class Success(val result: ForecastResult) : ForecastState
    data object Error : ForecastState
}
data class SubscriptionForecastSnapshot(val state: ForecastState, val rows: List<SubscriptionEntity>, val today: LocalDate) {
    constructor(forecast: ForecastResult, rows: List<SubscriptionEntity>) : this(ForecastState.Success(forecast),rows,forecast.fromInclusive)
    val forecast: ForecastResult? get() = (state as? ForecastState.Success)?.result
    init { require(state !is ForecastState.Success || state.result.fromInclusive == today) }
}

/** Only called around calculations; reading and mapping are deliberately outside this boundary. */
internal inline fun <T : Any> calculationOrNull(block: () -> T): T? = try { block() }
    catch (_: ArithmeticException) { null }
    catch (_: DateTimeException) { null }
    catch (_: com.glebskorobogatov.subscriptiontracker.domain.calendar.CalendarCalculationException) { null }

internal fun forecastSnapshot(rows: List<SubscriptionEntity>, today: LocalDate,
    forecast: ForecastEngine = ForecastEngine()): SubscriptionForecastSnapshot {
    val records = rows.map { it.toDomain() }
    val result = calculationOrNull { forecast.calculate(records,today) }
    return SubscriptionForecastSnapshot(result?.let(ForecastState::Success) ?: ForecastState.Error,rows,today)
}

data class SubscriptionDetails(val subscription: SubscriptionEntity,
    val period: com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionPeriod, val calendarError: Boolean = false)

internal fun detailsSnapshot(row: SubscriptionEntity, today: LocalDate): SubscriptionDetails {
    val record = row.toDomain()
    val period = calculationOrNull { com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionDomain().period(record,today) }
    return SubscriptionDetails(row,period ?: com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionPeriod(null,null,null),period == null)
}

/** No owned scope/cache. Caller owns DB and Settings lease; UI may stateIn its own lifecycle scope. */
class SubscriptionUseCases(
    private val database: TrackerDatabase,
    private val settingsRepository: SettingsRepository,
    val date: CurrentDate,
    private val lifecycle: LifecycleRepository = LifecycleRepository(database),
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
    val cancelled = groups.map { it.cancelled }.distinctUntilChanged()
    val archive = groups.map { it.archived }.distinctUntilChanged()
    val forecasts: Flow<SubscriptionForecastSnapshot> = combine(subscriptions, date.today) { rows, today ->
        forecastSnapshot(rows, today, forecast)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun details(id: UUID): Flow<SubscriptionDetails?> = combine(database.subscriptions().observeDetails(id), date.today) { row, today ->
        row?.let { detailsSnapshot(it,today) }
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
            editor.create(draft, recordedAt, date.today.value, image).also { image?.let { assetStore?.finish(it.id) } }
        } catch (error: Exception) {
            image?.let { if (database.assets().find(it.id) == null) assetStore?.removeFile(it) }
            throw error
        }
    }
    suspend fun editSubscription(expected: SubscriptionEntity, edit: SubscriptionEdit, changedAt: Instant) = reminderChanges.write {
        val prepared = (edit.image as? ImageEdit.Set)?.let { requireNotNull(assetStore).prepared(it.assetId) }
        try {
            editor.edit(expected, edit, changedAt, date.today.value, prepared).also {
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
    /** Target lifecycle operations, serialized with reminder reconciliation. */
    suspend fun changeLifecycle(expected: SubscriptionEntity, command: LifecycleCommand, changedAt: Instant) = reminderChanges.write { lifecycle.execute(expected, command, changedAt, date.today.value) }
    suspend fun deleteSubscription(expected: SubscriptionEntity, confirmed: Boolean) = reminderChanges.write {
        lifecycle.delete(expected, confirmed)
        expected.logoAssetId?.let { runCatching { cleanupAsset(it) } }
    }
    suspend fun updateSettings(transform: (Settings) -> Settings) =
        updateStoredSettings(settingsRepository, reminderChanges, transform)
    /** Calendar refresh never changes subscription status or writes to Room. */
    suspend fun refreshToday(today: LocalDate, changedAt: Instant) {
        reminderChanges.write { date.update(today) }
    }
}

/** Theme and animation edits keep the current reminder plan. Only offset membership reschedules it. */
internal fun settingsChangeReconcilesReminders(before: Settings, after: Settings) =
    before.renewalOffsets != after.renewalOffsets

internal suspend fun updateStoredSettings(
    repository: SettingsRepository,
    changes: com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderChanges,
    transform: (Settings) -> Settings,
) {
    changes.write(shouldNotify = { it }) {
        val before = repository.settings.first()
        repository.update(transform)
        val after = repository.settings.first()
        settingsChangeReconcilesReminders(before, after)
    }
}
