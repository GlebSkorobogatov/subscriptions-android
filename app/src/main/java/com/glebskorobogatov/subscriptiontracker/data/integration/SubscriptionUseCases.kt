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

/** The exact source rows provide all labels/appearance for both financial windows. */
data class SubscriptionForecastSnapshot(val forecast: ForecastResult, val rows: List<SubscriptionEntity>)
/** Distinguish calculation failures from exceptions raised while reading/mapping storage. */
internal class ForecastCalculationFailure : IllegalStateException("Forecast calculation failed")

data class SubscriptionDetails(val subscription: SubscriptionEntity, val period: com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionPeriod)

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
        val records = rows.map { it.toDomain() }
        val result = try { forecast.calculate(records, today) }
            catch (_: ArithmeticException) { throw ForecastCalculationFailure() }
            catch (_: DateTimeException) { throw ForecastCalculationFailure() }
            catch (_: com.glebskorobogatov.subscriptiontracker.domain.calendar.CalendarCalculationException) { throw ForecastCalculationFailure() }
        SubscriptionForecastSnapshot(result, rows)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun details(id: UUID): Flow<SubscriptionDetails?> = combine(database.subscriptions().observeDetails(id), date.today) { row, today ->
        row?.let { SubscriptionDetails(it, com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionDomain().period(it.toDomain(), today)) }
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
    suspend fun updateSettings(transform: (Settings) -> Settings) {
        reminderChanges.write(shouldNotify = { it }) {
            val before = settingsRepository.settings.first()
            settingsRepository.update(transform)
            val after = settingsRepository.settings.first()
            before.renewalOffsets != after.renewalOffsets
        }
    }
    /** Calendar refresh never changes subscription status or writes to Room. */
    suspend fun refreshToday(today: LocalDate, changedAt: Instant) {
        reminderChanges.write { date.update(today) }
    }
}
