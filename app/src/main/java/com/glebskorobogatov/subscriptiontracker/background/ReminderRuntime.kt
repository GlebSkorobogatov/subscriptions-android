package com.glebskorobogatov.subscriptiontracker.background

import android.content.Context
import com.glebskorobogatov.subscriptiontracker.app.ActiveDataset
import com.glebskorobogatov.subscriptiontracker.data.reminders.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.DatasetSettingsFactory
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

interface GenerationAccess {
    fun current(): UUID
    fun <T> ifCurrent(expected: UUID, action: () -> T): T?
}
class ActiveGeneration(context: Context) : GenerationAccess {
    private val active = ActiveDataset(context.applicationContext.filesDir)
    override fun current() = active.getOrCreate()
    override fun <T> ifCurrent(expected: UUID, action: () -> T): T? = active.ifCurrent(expected, action)
}
data class ScheduledReminder(val subscription: UUID, val fingerprint: String, val at: Instant)
interface ReminderWorkScheduler {
    suspend fun requestReconcile()
    suspend fun apply(generation: UUID, tasks: List<ScheduledReminder>, wake: Instant)
}

/** Opens no resources until invoked; independent of Activity and its ViewModels.
 * Dependencies are explicit so tests never resolve the production active pointer. */
class ReminderRuntime(
    context: Context,
    private val scheduler: ReminderWorkScheduler,
    private val generation: GenerationAccess = ActiveGeneration(context),
    private val publisher: ReminderPublisher = UnavailableReminderPublisher,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val beforePublication: suspend () -> Unit = {},
    private val openDatabase: (Context, UUID) -> TrackerDatabase = TrackerDatabaseFactory::open,
    private val acquireSettings: suspend (Context, UUID) -> DatasetSettingsFactory.Lease = DatasetSettingsFactory::acquire,
) {
    private val context = context.applicationContext
    private fun gate(id: UUID) = ReminderChanges.gate(context.getDatabasePath("subscriptions-$id.db").canonicalPath)

    private suspend fun <T> session(id: UUID, action: suspend (TrackerDatabase, DatasetSettingsFactory.Lease) -> T): T? {
        if (generation.current() != id) return null
        val db = openDatabase(context, id)
        var lease: DatasetSettingsFactory.Lease? = null
        try {
            lease = acquireSettings(context, id)
            if (generation.current() != id) return null
            return action(db, lease)
        } finally {
            withContext(NonCancellable) { try { db.close() } finally { lease?.close() } }
        }
    }

    suspend fun reconcile(expected: UUID? = null) = withContext(Dispatchers.IO) {
        val id = generation.current()
        if (expected != null && expected != id) {
            scheduler.requestReconcile()
            return@withContext
        }
        session(id) { db, lease ->
            ReminderReconciliation.run(context.getDatabasePath("subscriptions-$id.db").canonicalPath,
                snapshot = { gate(id).withLock {
                    val settings = lease.repository.settings.first()
                    val instant = now(); val localZone = zone()
                    generation.ifCurrent(id) {
                        val ledger = ReminderLedger(db, id)
                        ledger.reconcile(settings, instant, localZone, publisher)
                            .filter { it.failureCount < ReminderScheduling.MAX_FAILURES }
                            .groupBy { it.subscriptionId }.mapNotNull { (subscription, records) ->
                                val events = ledger.events(records)
                                val eventAt = ReminderScheduling.next(events, instant, localZone,
                                    publisher.available)
                                val recoveryAt = records.filter { it.state == DeliveryState.PROCESSING }
                                    .mapNotNull { it.leaseUntil }.filter { it > instant }.minOrNull()
                                val at = recoveryAt ?: eventAt ?: return@mapNotNull null
                                val notBefore = records.mapNotNull { it.notBefore }.maxOrNull()
                                ScheduledReminder(subscription, ReminderScheduling.fingerprint(events, localZone),
                                    if (notBefore != null && notBefore > at) notBefore else at)
                            }
                    }
                } },
                apply = { tasks ->
                    if (tasks != null && generation.current() == id)
                        scheduler.apply(id, tasks, ReminderScheduling.nextWake(now(), zone()))
                    if (generation.current() != id) scheduler.requestReconcile()
                })
        }
    }

    suspend fun process(id: UUID, subscription: UUID, fingerprint: String): PublicationResult? = withContext(Dispatchers.IO) {
        if (generation.current() != id) {
            scheduler.requestReconcile()
            return@withContext null
        }
        var result: PublicationResult? = null
        session(id) { db, lease ->
            val ledger = ReminderLedger(db, id)
            val claim = gate(id).withLock {
                val settings = lease.repository.settings.first()
                generation.ifCurrent(id) {
                    val records = ledger.reconcile(settings, now(), zone(), publisher).filter { it.subscriptionId == subscription &&
                        it.failureCount < ReminderScheduling.MAX_FAILURES }
                    if (ReminderScheduling.fingerprint(ledger.events(records), zone()) != fingerprint) null
                    else ledger.claim(subscription, settings, now(), zone())
                }
            }
            if (claim != null) {
                try {
                    beforePublication() // Test seam for crash/concurrent edit/generation switch.
                    gate(id).withLock {
                        val settings = lease.repository.settings.first()
                        currentCoroutineContext().ensureActive()
                        result = generation.ifCurrent(id) {
                            // Small synchronous critical section: cancellation cannot interrupt notify/ack.
                            ledger.publish(claim, settings, now(), zone(), publisher)
                        }
                    }
                } finally {
                    withContext(NonCancellable) { ledger.release(claim.token) }
                }
            }
        }
        if (generation.current() == id) reconcile(id)
        else scheduler.requestReconcile() // Never treat the new generation as this old job's session.
        result
    }
}
