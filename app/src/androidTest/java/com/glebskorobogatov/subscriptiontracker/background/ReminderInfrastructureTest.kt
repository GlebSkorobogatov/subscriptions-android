package com.glebskorobogatov.subscriptiontracker.background

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.reminders.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderInfrastructureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dataset: UUID
    private lateinit var db: TrackerDatabase
    private lateinit var lease: DatasetSettingsFactory.Lease
    private lateinit var access: TestGeneration
    private lateinit var scheduler: RecordingScheduler
    private var instant = Instant.parse("2027-01-01T00:00:00Z")
    private var localZone: ZoneId = ZoneOffset.UTC
    private val publications = AtomicInteger()
    private val messages = mutableListOf<com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderMessage>()
    private val publisher = ReminderPublisher { _, message ->
        publications.incrementAndGet(); synchronized(messages) { messages += message }; PublicationResult.PUBLISHED
    }
    private lateinit var sub: SubscriptionEntity

    @Before fun open(): Unit = runBlocking {
        dataset = UUID.randomUUID(); access = TestGeneration(dataset); scheduler = RecordingScheduler()
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset); db = TrackerDatabaseFactory.open(context,dataset); lease = DatasetSettingsFactory.acquire(context,dataset)
        sub = SubscriptionEntity(id = UUID.randomUUID(), name = "Одинаковое имя", accountLabel = "Личный", priceMinor = 49900, intervalUnit = FrequencyUnit.MONTH, intervalCount = 1, state = SubscriptionState.ACTIVE, anchorYear = 2027, anchorMonth = 1, anchorDay = 5, currentPeriodStartOverride = null, currentPeriodEnd = null, cancelledAccessEndExclusive = null, frozenCancelledPeriodStart = null, logoAssetId = null, catalogLogoId = null, createdAt = instant, updatedAt = instant, archivedAt = null)
        withContext(Dispatchers.IO) { db.subscriptions().insert(sub) }
    }
    @After fun close(): Unit = runBlocking {
        withContext(Dispatchers.IO) { db.close() }; lease.close()
        context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }
        context.preferencesFile(dataset).delete()
    }
    private fun Context.preferencesFile(id: UUID) = java.io.File(filesDir,"datastore/settings-$id.preferences_pb")
    private fun runtime(delivery: ReminderPublisher = publisher, hook: suspend () -> Unit = {}) =
        ReminderRuntime(context,scheduler,access,delivery,{ instant },{ localZone },hook)
    private fun task() = scheduler.tasks.single { it.subscription == sub.id }
    private suspend fun late(runtime: ReminderRuntime) {
        runtime.reconcile(); instant = Instant.parse("2027-01-04T10:00:00Z")
        val task = task(); runtime.process(dataset,sub.id,task.fingerprint)
    }
    private fun ledger() = ReminderLedger(db,dataset)

    @Test fun persistedReasonsRecoverWithoutActivityAndUseCurrentDaysRemaining(): Unit = runBlocking {
        late(runtime())
        assertEquals(1,publications.get()); assertEquals(1L,messages.single().reasons.single().daysRemaining)
        assertEquals(setOf(3,1),messages.single().coveredKeys.map { it.offsetDays }.toSet())
        assertEquals(2,db.deliveries().all().count { it.state == DeliveryState.DELIVERED })
    }
    @Test fun repeatWorkerDoesNotRepublishHandledKeys(): Unit = runBlocking {
        val run = runtime(); run.reconcile(); val original = task()
        instant = Instant.parse("2027-01-04T10:00:00Z")
        repeat(2) { run.process(dataset,sub.id,original.fingerprint) }
        assertEquals(1,publications.get())
    }
    @Test fun unavailablePublisherPreservesPendingAndDoesNotLoopImmediately(): Unit = runBlocking {
        val unavailable = object : ReminderPublisher {
            override val available = false
            override fun publish(identity: NotificationIdentity,
                message: com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderMessage) = PublicationResult.UNAVAILABLE
        }
        late(runtime(unavailable))
        assertEquals(0,publications.get()); assertTrue(db.deliveries().all().all { it.state == DeliveryState.PENDING && it.deliveredAt == null })
        assertTrue(scheduler.tasks.isEmpty())
    }
    @Test fun durableReasonsSurviveDatabaseAndDataStoreReopen(): Unit = runBlocking {
        runtime().reconcile()
        db.close(); lease.close()
        db = TrackerDatabaseFactory.open(context,dataset); lease = DatasetSettingsFactory.acquire(context,dataset)
        instant = Instant.parse("2027-01-04T10:00:00Z")
        val saved = task(); runtime().process(dataset,sub.id,saved.fingerprint)
        assertEquals(1,publications.get())
    }
    @Test fun changedDateInvalidatesOldWorker(): Unit = runBlocking {
        val run = runtime(); run.reconcile(); val old = task()
        db.subscriptions().update(sub.copy(anchorDay = 9))
        instant = Instant.parse("2027-01-04T10:00:00Z")
        run.process(dataset,sub.id,old.fingerprint)
        assertEquals(0,publications.get()); assertTrue(db.deliveries().all().none { it.state == DeliveryState.DELIVERED })
    }
    @Test fun archiveInvalidatesClaimImmediatelyBeforePublication(): Unit = runBlocking {
        late(runtime(hook = { db.subscriptions().update(sub.copy(state = SubscriptionState.ARCHIVED)) }))
        assertEquals(0,publications.get()); assertTrue(db.deliveries().all().all { it.state == DeliveryState.PENDING && it.claimToken == null }); assertTrue(scheduler.tasks.isEmpty())
    }
    @Test fun deleteInvalidatesClaimAndCascadesPendingLedger(): Unit = runBlocking {
        late(runtime(hook = { db.subscriptions().delete(sub.id) }))
        assertEquals(0,publications.get()); assertTrue(db.deliveries().all().isEmpty())
    }
    @Test fun mismatchedGenerationDoesNotOpenOrWriteNewDataset(): Unit = runBlocking {
        val run = runtime(); run.reconcile(); val old = task(); val other = UUID.randomUUID()
        access.id = other; run.process(dataset,sub.id,old.fingerprint)
        assertFalse(context.getDatabasePath("subscriptions-$other.db").exists())
        assertEquals(0,publications.get()); assertTrue(db.deliveries().all().all { it.state == DeliveryState.PENDING })
        assertEquals(1,scheduler.requests)
    }
    @Test fun generationRecheckedAfterReservationBeforeExternalAction(): Unit = runBlocking {
        late(runtime(hook = { access.id = UUID.randomUUID() }))
        assertEquals(0,publications.get()); assertTrue(db.deliveries().all().none { it.state == DeliveryState.DELIVERED })
    }
    @Test fun twoConnectionsCannotReserveSameSubscriptionConcurrently(): Unit = runBlocking {
        val run = runtime(); run.reconcile(); instant = Instant.parse("2027-01-04T10:00:00Z")
        val other = TrackerDatabaseFactory.open(context,dataset)
        try {
            val settings = lease.repository.settings.first()
            val start = CompletableDeferred<Unit>()
            val claims = listOf(db,other).map { connection -> async(Dispatchers.IO) {
                start.await(); ReminderLedger(connection,dataset).claim(sub.id,settings,instant,localZone)
            } }
            start.complete(Unit)
            assertEquals(1,claims.awaitAll().count { it != null })
        } finally { other.close() }
    }
    @Test fun expiredReservationCanBeRecoveredAndOldOwnerCannotAcknowledge(): Unit = runBlocking {
        runtime().reconcile(); instant = Instant.parse("2027-01-04T10:00:00Z")
        val settings = lease.repository.settings.first(); val first = ledger().claim(sub.id,settings,instant,localZone)!!
        db.close(); db = TrackerDatabaseFactory.open(context,dataset)
        runtime().reconcile()
        assertEquals(instant.plusSeconds(300),task().at)
        instant = instant.plusSeconds(301)
        val next = ledger().claim(sub.id,settings,instant,localZone)!!
        assertNull(ledger().publish(first,settings,instant,localZone,publisher))
        assertEquals(PublicationResult.PUBLISHED,ledger().publish(next,settings,instant,localZone,publisher))
        assertEquals(1,publications.get())
    }
    @Test fun cancellationReleasesClaimAndClosesBackgroundSession(): Unit = runBlocking {
        val claimed = CompletableDeferred<Unit>(); val keep = CompletableDeferred<Unit>()
        val run = runtime(hook = { claimed.complete(Unit); keep.await() })
        run.reconcile(); val saved = task(); instant = Instant.parse("2027-01-04T10:00:00Z")
        val job = launch(Dispatchers.IO) { run.process(dataset,sub.id,saved.fingerprint) }
        claimed.await(); job.cancelAndJoin()
        assertTrue(db.deliveries().all().all { it.claimToken == null && it.state == DeliveryState.PENDING })
        lease.repository.update { it.copy(renewalOffsets = setOf(0)) }
        assertEquals(setOf(0),lease.repository.settings.first().renewalOffsets)
    }
    @Test fun retryablePublisherFailureIsBoundedAcrossNewRuntimeInstances(): Unit = runBlocking {
        val failures = AtomicInteger()
        val bad = ReminderPublisher { _, _ -> failures.incrementAndGet(); PublicationResult.RETRYABLE_FAILURE }
        runtime(bad).reconcile(); instant = Instant.parse("2027-01-04T10:00:00Z")
        repeat(3) {
            val current = task(); runtime(bad).process(dataset,sub.id,current.fingerprint)
            instant = instant.plusSeconds(120)
        }
        runtime(bad).reconcile()
        assertEquals(3,failures.get()); assertTrue(scheduler.tasks.isEmpty())
        assertTrue(db.deliveries().all().all { it.failureCount == 3 && it.state == DeliveryState.PENDING })
    }
    @Test fun publicationExceptionIsRecordedAsRetryableNotDelivered(): Unit = runBlocking {
        late(runtime(ReminderPublisher { _, _ -> error("publisher failed") }))
        assertTrue(db.deliveries().all().all { it.failureCount == 1 && it.state == DeliveryState.PENDING })
    }
    @Test fun timezoneChangesPreserveKeysAndFixedNineButReplaceScheduling() = runBlocking {
        runtime().reconcile(); val initial = task(); val ids = db.deliveries().all().map { it.id }
        localZone = ZoneId.of("Asia/Yekaterinburg"); runtime().reconcile(); val moved = task()
        assertNotEquals(initial.fingerprint,moved.fingerprint); assertNotEquals(initial.at,moved.at)
        assertEquals(LocalTime.of(9,0),moved.at.atZone(localZone).toLocalTime())
        runtime().reconcile(); assertEquals(ids,db.deliveries().all().map { it.id })
        assertEquals(moved,task())
    }

    @Test fun expiredEventIsNotRecoveredAsOldWarning(): Unit = runBlocking {
        runtime().reconcile(); instant = Instant.parse("2027-01-06T10:00:00Z"); runtime().reconcile()
        assertTrue(db.deliveries().all().all { it.state == DeliveryState.PENDING }); assertTrue(scheduler.tasks.isEmpty()); assertEquals(0,publications.get())
    }
    @Test fun sameNamesHaveIndependentWorkAndLedgerKeys(): Unit = runBlocking {
        val other = sub.copy(id = UUID.randomUUID(),accountLabel = "Семейный")
        db.subscriptions().insert(other); runtime().reconcile()
        assertEquals(setOf(sub.id,other.id),scheduler.tasks.map { it.subscription }.toSet())
        assertEquals(4,db.deliveries().all().size)
    }
    @Test fun onlyRenewalReasonsEnterCoveredKeys() = runBlocking {
        db.subscriptions().update(sub.copy())
        late(runtime())
        assertEquals(1,publications.get()); assertEquals(1,messages.single().reasons.size)
        assertEquals(2,messages.single().coveredKeys.size)
        assertTrue(db.deliveries().all().all { it.eventType == ReminderEventType.RENEWAL })
    }

    @Test fun noActivitySessionRequiredAndExistingDataStoreLeaseRemainsUsable(): Unit = runBlocking {
        runtime().reconcile(); runtime().reconcile()
        assertEquals(2,db.deliveries().all().size)
        lease.repository.update { it.copy(renewalOffsets = setOf(0)) }
        runtime().reconcile(); assertEquals(setOf(0),db.deliveries().all().map { it.offsetDays }.toSet())
    }
    @Test fun unsupportedPayloadFailsWithoutMarkingDelivered(): Unit = runBlocking {
        runtime().reconcile(); val record = db.deliveries().all().first()
        db.deliveries().update(record.copy(payloadVersion = 99))
        try { runtime().reconcile(); fail("Corruption must not become empty success") } catch (_: IllegalArgumentException) { }
        assertEquals(0,publications.get()); assertTrue(db.deliveries().all().none { it.state == DeliveryState.DELIVERED })
    }
    @Test fun changedDateBetweenClaimAndPublishDoesNotProduceStaleMessage(): Unit = runBlocking {
        late(runtime(hook = { db.subscriptions().update(sub.copy(anchorDay = 9)) }))
        assertEquals(0,publications.get())
    }
    @Test fun futurePlanIsBoundedInsteadOfCreatingYearsOfWork(): Unit = runBlocking {
        db.subscriptions().update(sub.copy(anchorMonth = 12))
        runtime().reconcile(); assertTrue(scheduler.tasks.isEmpty()); assertTrue(db.deliveries().all().isEmpty())
        assertEquals(Instant.parse("2027-01-02T00:05:00Z"),scheduler.wake)
    }

    @Test fun simultaneousWorkersPublishOneCurrentMessage(): Unit = runBlocking {
        val run = runtime(); run.reconcile(); val saved = task()
        instant = Instant.parse("2027-01-04T10:00:00Z")
        val start = CompletableDeferred<Unit>()
        val workers = List(2) { async(Dispatchers.IO) { start.await(); runtime().process(dataset,sub.id,saved.fingerprint) } }
        start.complete(Unit); workers.awaitAll()
        assertEquals(1,publications.get()); assertEquals(2,db.deliveries().all().count { it.state == DeliveryState.DELIVERED })
    }
    @Test fun settingsFailureClosesAlreadyOpenedBackgroundDatabase(): Unit = runBlocking {
        var opened: TrackerDatabase? = null
        val failing = ReminderRuntime(context,scheduler,access,now = { instant },zone = { localZone },
            openDatabase = { ctx, id -> TrackerDatabaseFactory.open(ctx,id).also { opened = it } },
            acquireSettings = { _, _ -> throw java.io.IOException("temporary settings failure") })
        try { failing.reconcile(); fail("Expected read failure") } catch (_: java.io.IOException) { }
        assertNotNull(opened); assertFalse(opened!!.isOpen)
        assertEquals(Settings(),lease.repository.settings.first())
    }
    @Test fun roomOpenFailureDoesNotAccessSettingsOrReportDelivery(): Unit = runBlocking {
        var acquired = false
        val failing = ReminderRuntime(context,scheduler,access,now = { instant },zone = { localZone },
            openDatabase = { _, _ -> throw android.database.sqlite.SQLiteException("temporary open failure") },
            acquireSettings = { _, _ -> acquired = true; error("must not acquire") })
        try { failing.reconcile(); fail("Expected DB failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertFalse(acquired); assertEquals(0,publications.get()); assertTrue(scheduler.tasks.isEmpty())
    }

    @Test fun productionUseCasesSignalLifecycleChangesButNotThemeOrChartSettings(): Unit = runBlocking {
        var signals = 0
        val use = com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases(db,lease.repository,
            com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate(LocalDate.of(2027,1,1)),
            reminderChanges = ReminderChanges(request = { signals++ }))
        use.updateSettings { it.copy(theme = Theme.DARK) }
        assertEquals(0,signals)
        use.updateSettings { it.copy(renewalOffsets = setOf(0)) }
        assertEquals(1,signals)
        use.changeLifecycle(sub,LifecycleCommand.Archive,instant)
        assertEquals(2,signals)
        try { use.changeLifecycle(sub,LifecycleCommand.Archive,instant); fail("stale snapshot") }
        catch (_: com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict) { }
        assertEquals(2,signals)
    }
    @Test fun springClockTransitionKeepsNineAndOriginalLogicalDate() = runBlocking {
        localZone = ZoneId.of("Europe/Amsterdam"); instant = Instant.parse("2027-03-27T12:00:00Z")
        db.subscriptions().update(sub.copy(anchorMonth = 3,anchorDay = 29))
        lease.repository.update { it.copy(renewalOffsets = setOf(1)) }
        runtime().reconcile(); val saved = task()
        assertEquals(Instant.parse("2027-03-28T07:00:00Z"),saved.at)
        instant = Instant.parse("2027-03-28T07:05:00Z"); runtime().process(dataset,sub.id,saved.fingerprint)
        assertEquals(LocalDate.of(2027,3,29),messages.single().reasons.single().targetDate)
        assertEquals(1,publications.get())
    }

    @Test fun autumnClockTransitionKeepsNineAndDoesNotPublishAgain() = runBlocking {
        localZone = ZoneId.of("Europe/Amsterdam"); instant = Instant.parse("2027-10-30T12:00:00Z")
        db.subscriptions().update(sub.copy(anchorMonth = 10,anchorDay = 31))
        lease.repository.update { it.copy(renewalOffsets = setOf(0)) }
        runtime().reconcile(); val saved = task()
        assertEquals(Instant.parse("2027-10-31T08:00:00Z"),saved.at)
        instant = Instant.parse("2027-10-31T08:05:00Z"); runtime().process(dataset,sub.id,saved.fingerprint)
        instant = Instant.parse("2027-10-31T09:05:00Z"); runtime().process(dataset,sub.id,saved.fingerprint)
        assertEquals(1,publications.get())
    }


    @Test fun obsoleteIndividualOffsetIsRetainedButCannotCreateNotification() = runBlocking {
        val old = NotificationDeliveryEntity(UUID.randomUUID(),sub.id,ReminderEventType.RENEWAL,
            LocalDate.of(2027,1,5),0,LocalDate.of(2027,1,5),null,DeliveryState.PENDING,dataset,"09:00")
        db.deliveries().insert(old)
        repeat(2) { runtime().reconcile() }
        assertEquals(DeliveryState.PENDING,db.deliveries().find(old.id)!!.state)
        assertEquals(3,db.deliveries().all().size)
        assertEquals(setOf(3,1),ledger().reconcile(lease.repository.settings.first(),instant,localZone).map { it.offsetDays }.toSet())
        late(runtime()); assertEquals(1,publications.get()); assertEquals(2,messages.single().coveredKeys.size)
    }
    @Test fun oldPendingTimeMovesToNineWithoutReplacingLogicalKeysOrDuplicatingDelivery() = runBlocking {
        runtime().reconcile(); val initial = db.deliveries().all()
        initial.forEach { db.deliveries().update(it.copy(plannedLocalTime = "18:00")) }
        runtime().reconcile()
        assertEquals(initial.map { it.id }.toSet(),db.deliveries().all().map { it.id }.toSet())
        assertTrue(db.deliveries().all().all { it.plannedLocalTime == "09:00" })
        late(runtime()); assertEquals(1,publications.get())
        runtime().reconcile()
        assertEquals(initial.map { it.id }.toSet(),db.deliveries().all().map { it.id }.toSet())
        assertTrue(db.deliveries().all().all { it.state == DeliveryState.DELIVERED })
    }

    @Test fun disablingThenReenablingGlobalDaysReschedulesPendingKeysWithoutClearingLedger() = runBlocking {
        runtime().reconcile(); val before = db.deliveries().all()
        lease.repository.update { it.copy(renewalOffsets = emptySet()) }; runtime().reconcile()
        assertTrue(scheduler.tasks.isEmpty()); assertEquals(before.map { it.id }.toSet(),db.deliveries().all().map { it.id }.toSet())
        lease.repository.update { it.copy(renewalOffsets = setOf(3,1)) }; runtime().reconcile()
        assertEquals(1,scheduler.tasks.size); assertEquals(before.map { it.id }.toSet(),db.deliveries().all().map { it.id }.toSet())
        late(runtime()); assertEquals(1,publications.get())
        lease.repository.update { it.copy(renewalOffsets = emptySet()) }; runtime().reconcile()
        lease.repository.update { it.copy(renewalOffsets = setOf(3,1)) }; runtime().reconcile()
        assertEquals(1,publications.get()); assertTrue(scheduler.tasks.isEmpty())
        assertTrue(db.deliveries().all().all { it.state == DeliveryState.DELIVERED })
    }

    class TestGeneration(@Volatile var id: UUID) : GenerationAccess {
        override fun current() = id
        @Synchronized override fun <T> ifCurrent(expected: UUID, action: () -> T): T? = if (id == expected) action() else null
    }
    class RecordingScheduler : ReminderWorkScheduler {
        var tasks = emptyList<ScheduledReminder>(); var wake: Instant? = null; var requests = 0
        override suspend fun requestReconcile() { requests++ }
        override suspend fun apply(generation: UUID, tasks: List<ScheduledReminder>, wake: Instant) {
            this.tasks = tasks; this.wake = wake
        }
    }
}
