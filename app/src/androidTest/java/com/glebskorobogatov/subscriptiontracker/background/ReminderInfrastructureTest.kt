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
        assertTrue(db.deliveries().all().all { it.state == DeliveryState.SKIPPED }); assertTrue(scheduler.tasks.isEmpty()); assertEquals(0,publications.get())
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
        runtime().reconcile()
        assertEquals(setOf(0),ledger().reconcile(lease.repository.settings.first(),instant,localZone).map { it.offsetDays }.toSet())
        assertEquals(setOf(0,1,3),db.deliveries().all().map { it.offsetDays }.toSet())
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
        use.updateSettings { it.copy(animationsEnabled = false) }
        assertEquals(0,signals)
        assertEquals(Theme.DARK,lease.repository.settings.first().theme)
        assertFalse(lease.repository.settings.first().animationsEnabled)
        use.updateSettings { it.copy(renewalOffsets = setOf(0)) }
        assertFalse(lease.repository.settings.first().animationsEnabled)
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


    private suspend fun dailyAllOffsets() {
        db.subscriptions().update(sub.copy(intervalUnit = FrequencyUnit.DAY,anchorDay = 1))
        lease.repository.update { it.copy(renewalOffsets = setOf(7,3,1,0)) }
    }
    @Test fun rollingDailyWindowPersistsTwentyEightKeysButOneSubscriptionTask() = runBlocking {
        dailyAllOffsets(); repeat(2) { runtime().reconcile() }
        assertEquals(28,db.deliveries().all().size)
        assertEquals(1,scheduler.tasks.size)
        assertEquals(LocalDate.of(2027,1,14),db.deliveries().all().maxOf { it.eventDate })
    }
    @Test fun simultaneousShortCyclesPublishOneMessageAndAcknowledgeOnlyFourShownKeys() = runBlocking {
        dailyAllOffsets(); val run = runtime(); run.reconcile(); val first = task()
        instant = Instant.parse("2027-01-01T09:00:00Z")
        assertEquals(PublicationResult.PUBLISHED,run.process(dataset,sub.id,first.fingerprint))
        assertEquals(1,publications.get())
        assertEquals(listOf(0L,1L,3L,7L),messages.single().reasons.map { it.daysRemaining })
        assertEquals(4,db.deliveries().all().count { it.state == DeliveryState.DELIVERED })
        repeat(2) { run.process(dataset,sub.id,first.fingerprint) }
        assertEquals(1,publications.get())
        assertTrue(task().at > instant)
    }
    @Test fun longShutdownAggregatesCurrentPersistedCyclesWithoutCatchupSeries() = runBlocking {
        dailyAllOffsets(); val run = runtime(); run.reconcile()
        instant = Instant.parse("2027-01-04T12:00:00Z"); run.reconcile()
        run.process(dataset,sub.id,task().fingerprint)
        assertEquals(1,publications.get())
        val message = messages.single()
        assertTrue(message.reasons.size > 1)
        assertTrue(message.reasons.all { it.targetDate >= LocalDate.of(2027,1,4) })
        assertTrue(message.coveredKeys.size <= 15)
        assertEquals(message.coveredKeys.size,db.deliveries().all().count { it.state == DeliveryState.DELIVERED })
        repeat(3) { run.reconcile() }
        assertEquals(1,publications.get()); assertTrue(task().at > instant)
    }
    @Test fun initialSaveAfterNineDoesNotCreateBackdatedDailyDelivery() = runBlocking {
        dailyAllOffsets(); instant = Instant.parse("2027-01-04T12:00:00Z")
        val run = runtime(); run.reconcile()
        assertTrue(db.deliveries().all().all { it.plannedLocalDate >= LocalDate.of(2027,1,5) })
        assertNull(ledger().claim(sub.id,lease.repository.settings.first(),instant,localZone))
        assertEquals(0,publications.get())
    }
    @Test fun recoverySuppressionDoesNotFalselyMarkUnshownBackoffKeyDelivered() = runBlocking {
        runtime().reconcile(); instant = Instant.parse("2027-01-04T10:00:00Z")
        val deferred = db.deliveries().all().first { it.offsetDays == 3 }
        db.deliveries().update(deferred.copy(failureCount = 1,notBefore = instant.plusSeconds(120)))
        val settings = lease.repository.settings.first()
        val claim = ledger().claim(sub.id,settings,instant,localZone)!!
        assertEquals(setOf(1),claim.message.coveredKeys.map { it.offsetDays }.toSet())
        assertEquals(PublicationResult.PUBLISHED,ledger().publish(claim,settings,instant,localZone,publisher))
        assertEquals(DeliveryState.SKIPPED,db.deliveries().find(deferred.id)!!.state)
        assertNull(db.deliveries().find(deferred.id)!!.deliveredAt)
        instant = instant.plusSeconds(121); runtime().reconcile()
        assertTrue(scheduler.tasks.isEmpty()); assertEquals(1,publications.get())
    }
    @Test fun acknowledgedKeysSurviveDateRollbackAndOffsetReenable() = runBlocking {
        late(runtime()); val handled = db.deliveries().all().map { it.id }.toSet()
        instant = Instant.parse("2028-01-01T00:00:00Z"); ledger().cleanup(instant,localZone)
        instant = Instant.parse("2027-01-04T10:00:00Z")
        lease.repository.update { it.copy(renewalOffsets = emptySet()) }; runtime().reconcile()
        lease.repository.update { it.copy(renewalOffsets = setOf(3,1)) }; runtime().reconcile()
        assertEquals(handled,db.deliveries().all().map { it.id }.toSet())
        assertTrue(db.deliveries().all().all { it.state == DeliveryState.DELIVERED })
        assertEquals(1,publications.get()); assertTrue(scheduler.tasks.isEmpty())
    }
    @Test fun boundedCleanupKeepsLogicalKeysAndLiveClaims() = runBlocking {
        val today = LocalDate.of(2027,1,1)
        val old = NotificationDeliveryEntity(UUID.randomUUID(),sub.id,ReminderEventType.RENEWAL,today.minusDays(1),
            0,today.minusDays(1),null,DeliveryState.PENDING,dataset)
        db.runInTransaction { repeat(300) { n ->
            db.deliveries().insert(old.copy(id = UUID.randomUUID(),eventDate = today.minusDays(n+1L),plannedLocalDate = today.minusDays(n+1L)))
        } }
        val token = UUID.randomUUID()
        val live = old.copy(id = UUID.randomUUID(),eventDate = today.minusDays(301),plannedLocalDate = today.minusDays(301),
            state = DeliveryState.PROCESSING,claimToken = token,leaseUntil = instant.plusSeconds(300))
        db.deliveries().insert(live)
        assertEquals(ReminderLedger.CLEANUP_BATCH,ledger().cleanup(instant,localZone))
        assertEquals(301,db.deliveries().all().size)
        assertEquals(256,db.deliveries().all().count { it.state == DeliveryState.SKIPPED })
        assertEquals(live,db.deliveries().find(live.id))
        assertEquals(44,ledger().cleanup(instant,localZone))
        assertEquals(0,ledger().cleanup(instant,localZone))
        assertEquals(301,db.deliveries().all().size)
    }
    @Test fun expiredUncertainClaimBecomesSkippedNeverDeliveredOrDeleted() = runBlocking {
        val old = NotificationDeliveryEntity(UUID.randomUUID(),sub.id,ReminderEventType.RENEWAL,LocalDate.of(2026,12,31),
            0,LocalDate.of(2026,12,31),null,DeliveryState.PROCESSING,dataset,claimToken = UUID.randomUUID(),
            leaseUntil = instant.minusSeconds(1),failureCount = 3,notBefore = instant.minusSeconds(1))
        db.deliveries().insert(old); assertEquals(1,ledger().cleanup(instant,localZone))
        val cleaned = db.deliveries().find(old.id)!!
        assertEquals(DeliveryState.SKIPPED,cleaned.state); assertNull(cleaned.deliveredAt)
        assertNull(cleaned.claimToken); assertNull(cleaned.leaseUntil); assertEquals(0,cleaned.failureCount)
    }
    @Test fun foreignGenerationIsNotCompactedByCurrentLedger() = runBlocking {
        val other = NotificationDeliveryEntity(UUID.randomUUID(),sub.id,ReminderEventType.RENEWAL,LocalDate.of(2026,12,31),
            0,LocalDate.of(2026,12,31),null,DeliveryState.PENDING,UUID.randomUUID())
        db.deliveries().insert(other); assertEquals(0,ledger().cleanup(instant,localZone))
        assertEquals(other,db.deliveries().find(other.id))
    }
    @Test fun currentClaimRetainsFreshPriceWhenDailyEventsOverlap() = runBlocking {
        dailyAllOffsets()
        var price = 0L
        val fresh = object : ReminderPublisher {
            override fun publish(identity: NotificationIdentity, message: com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderMessage) = error("Presentation required")
            override fun publish(identity: NotificationIdentity, message: com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderMessage,
                presentation: ReminderPresentation): PublicationResult { price = presentation.price.amountMinor; return PublicationResult.PUBLISHED }
        }
        val run = runtime(fresh,hook = { db.subscriptions().update(db.subscriptions().find(sub.id)!!.copy(priceMinor = 12345)) })
        run.reconcile(); val first = task(); instant = Instant.parse("2027-01-01T09:00:00Z")
        assertEquals(PublicationResult.PUBLISHED,run.process(dataset,sub.id,first.fingerprint))
        assertEquals(12345L,price)
    }
    @Test fun oneUnrepresentableSeriesAndFinancialOverflowDoNotStopHealthyReminders() = runBlocking {
        val invalid = sub.copy(id = UUID.randomUUID(),intervalUnit = FrequencyUnit.YEAR,intervalCount = Int.MAX_VALUE,
            anchorYear = LocalDate.MIN.year,anchorMonth = 1,anchorDay = 1)
        db.subscriptions().insert(invalid)
        db.subscriptions().update(sub.copy(priceMinor = Long.MAX_VALUE))
        late(runtime()); assertEquals(1,publications.get())
        assertEquals(sub.id,messages.single().subscriptionId.value)
        assertTrue(db.deliveries().all().none { it.subscriptionId == invalid.id })
    }
    @Test fun syntheticHundredDailySubscriptionsAndFiveThousandExpiredKeysRemainBounded() = runBlocking {
        dailyAllOffsets(); val daily = db.subscriptions().find(sub.id)!!
        db.runInTransaction {
            repeat(99) { db.subscriptions().insert(daily.copy(id = UUID.randomUUID())) }
            repeat(5000) { n ->
                val date = LocalDate.of(2026,12,31).minusDays(n.toLong())
                db.deliveries().insert(NotificationDeliveryEntity(UUID.randomUUID(),sub.id,ReminderEventType.RENEWAL,
                    date,0,date,null,DeliveryState.PENDING,dataset))
            }
        }
        val start = System.nanoTime(); val run = runtime(); run.reconcile(); run.reconcile()
        val elapsed = (System.nanoTime()-start)/1_000_000.0
        assertEquals(100,scheduler.tasks.size); assertEquals(7800,db.deliveries().all().size)
        assertEquals(512,db.deliveries().all().count { it.state == DeliveryState.SKIPPED })
        assertEquals(2800,db.deliveries().all().count { it.eventDate >= LocalDate.of(2027,1,1) })
        println("R2b synthetic Android: logical=2800 tasks=100 ledger=7800 cleanup=256 two reconciliations ms=$elapsed")
    }


    @Test fun cleanupRechecksFractionalLeaseAsInstantBeforeTouchingClaim() = runBlocking {
        val old = NotificationDeliveryEntity(UUID.randomUUID(),sub.id,ReminderEventType.RENEWAL,LocalDate.of(2026,12,31),
            0,LocalDate.of(2026,12,31),null,DeliveryState.PROCESSING,dataset,claimToken = UUID.randomUUID(),
            leaseUntil = instant.plusNanos(100_000_000))
        db.deliveries().insert(old)
        assertEquals(0,ledger().cleanup(instant,localZone))
        assertEquals(old,db.deliveries().find(old.id))
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
