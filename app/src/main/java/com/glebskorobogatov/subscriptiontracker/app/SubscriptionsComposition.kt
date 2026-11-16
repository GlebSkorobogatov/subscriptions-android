package com.glebskorobogatov.subscriptiontracker.app

import android.content.Context
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.DatasetSettingsFactory
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Activity ViewModel owns the collection. Rotation retains it; final cancellation joins children
 * before finally releases Room and the DataStore lease. No resource is acquired in composition. */
class SubscriptionsComposition(context: Context,
    private val reconcile: (suspend () -> Unit)? = null,
    private val activeDataset: () -> java.util.UUID = { ActiveDataset(context.applicationContext.filesDir).getOrCreate() },
) : SubscriptionsSource {
    private val gate = Mutex()
    private val readers = mutableSetOf<Job>()
    @Volatile private var datasetId: java.util.UUID? = null
    override fun activeDatasetId() = datasetId
    @Volatile private var current: SubscriptionUseCases? = null
    override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T = withContext(Dispatchers.IO) {
        gate.withLock {
            val use = checkNotNull(current) { "Session unavailable" }
            val id = checkNotNull(datasetId)
            // Future pointer switches must acquire this same dataset mutation gate first.
            requireCurrent(id)
            block(use)
        }
    }
    override fun settings() = sessionFlow { it.settings }
    override fun finance() = sessionFlow { com.glebskorobogatov.subscriptiontracker.ui.finance.financeSnapshots(it) }
    override fun archive() = sessionFlow { com.glebskorobogatov.subscriptiontracker.ui.archive.archiveRows(it) }
    override fun details(id: java.util.UUID) = sessionFlow { it.details(id) }
    private fun <T> sessionFlow(values: (SubscriptionUseCases) -> Flow<T>): Flow<T> = channelFlow {
        val reader = gate.withLock {
            val use = checkNotNull(current) { "Session unavailable" }
            requireCurrent(checkNotNull(datasetId))
            launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try { values(use).collect { send(it) } }
                catch (error: Exception) { close(if (error is CancellationException) IllegalStateException("Session closed",error) else error) }
            }.also { readers += it; it.start() }
        }
        try { reader.join() } finally {
            withContext(NonCancellable) {
                reader.cancelAndJoin()
                gate.withLock { readers -= reader }
            }
        }
    }
    private fun mutationGate(id: java.util.UUID) = com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderChanges.gate(
        context.getDatabasePath("subscriptions-$id.db").canonicalPath)
    private fun requireCurrent(id: java.util.UUID) {
        if (ActiveDataset.guarded(id, activeDataset) { true } != true) throw StorageUnavailable(StorageFailure.STALE_GENERATION)
    }
    override fun today(): LocalDate = current?.date?.today?.value ?: LocalDate.now()
    private val context = context.applicationContext

    override fun observe(refreshes: Flow<Unit>): Flow<SubscriptionsUiState> = flow {
        val id = try { activeDataset() } catch (error: Exception) { throw storageFailure(error) }
        val db = mutationGate(id).withLock {
            ActiveDataset.guarded(id, activeDataset) { TrackerDatabaseFactory.open(context, id) }
                ?: throw StorageUnavailable(StorageFailure.STALE_GENERATION)
        }
        var lease: DatasetSettingsFactory.Lease? = null
        try {
            val settings = DatasetSettingsFactory.acquire(context, id).also { lease = it }
            val changes = com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderChanges(
                com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderChanges.gate(
                    context.getDatabasePath("subscriptions-$id.db").canonicalPath), validate = { requireCurrent(id) }, request = reconcile ?: {})
            val assets = com.glebskorobogatov.subscriptiontracker.data.assets.AssetStore(context, id, activeDataset)
            mutationGate(id).withLock {
                ActiveDataset.guarded(id, activeDataset) {
                    // A failed query aborts initialization; it must never become an empty set.
                    val known = db.assets().all().map { it.relativePath }.toSet()
                    assets.sweep(known)
                } ?: throw StorageUnavailable(StorageFailure.STALE_GENERATION)
            }
            val useCases = SubscriptionUseCases(db, settings.repository, CurrentDate(LocalDate.now()), reminderChanges = changes,
                assetStore = assets)
            gate.withLock { requireCurrent(id); current = useCases; datasetId = id }
            emitAll(observeSession(useCases, refreshes))
        } catch (error: Exception) { throw storageFailure(error)
        } finally {
            withContext(NonCancellable) {
                gate.withLock {
                    current = null; datasetId = null
                    readers.forEach { it.cancel() }
                    readers.toList().joinAll()
                    try { db.close() } finally { lease?.close() }
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}

/** collectLatest cancels and joins the old readers before refreshing the date. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeSession(useCases: SubscriptionUseCases, refreshes: Flow<Unit>,
    today: () -> LocalDate = { LocalDate.now() }, now: () -> Instant = { Instant.now() }): Flow<SubscriptionsUiState> =
    refreshes.onStart { emit(Unit) }.transformLatest {
        useCases.refreshToday(today(), now())
        emitAll(subscriptionStates(useCases))
    }
