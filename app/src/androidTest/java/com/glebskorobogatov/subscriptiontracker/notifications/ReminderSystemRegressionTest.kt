package com.glebskorobogatov.subscriptiontracker.notifications

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.glebskorobogatov.subscriptiontracker.background.*
import com.glebskorobogatov.subscriptiontracker.data.reminders.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*

/** F-04: real SQLite transactions and native notifications, isolated UUIDs, controlled barriers. */
class ReminderSystemRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val publisher = AndroidReminderPublisher(context)
    private lateinit var id: UUID
    private lateinit var db: TrackerDatabase
    private lateinit var lease: DatasetSettingsFactory.Lease
    private lateinit var generation: ReminderInfrastructureTest.TestGeneration
    private lateinit var scheduler: ReminderInfrastructureTest.RecordingScheduler
    private lateinit var sub: SubscriptionEntity
    private var now = Instant.parse("2027-01-01T00:00:00Z")
    private var zone: ZoneId = ZoneId.of("Europe/Amsterdam")
    @Before fun open(): Unit = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        id = UUID.randomUUID(); generation = ReminderInfrastructureTest.TestGeneration(id)
        scheduler = ReminderInfrastructureTest.RecordingScheduler()
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, id); db = TrackerDatabaseFactory.open(context,id); lease = DatasetSettingsFactory.acquire(context,id)
        sub = SubscriptionEntity(id = UUID.randomUUID(), name = "Вымышленный сервис", accountLabel = "Тест", priceMinor = 39900, intervalUnit = FrequencyUnit.MONTH, intervalCount = 1, state = SubscriptionState.ACTIVE, anchorYear = 2027, anchorMonth = 1, anchorDay = 5, currentPeriodStartOverride = null, currentPeriodEnd = null, cancelledAccessEndExclusive = null, frozenCancelledPeriodStart = null, logoAssetId = null, catalogLogoId = null, createdAt = now, updatedAt = now, archivedAt = null)
        withContext(Dispatchers.IO) { db.subscriptions().insert(sub) }
        publisher.createChannels()
    }
    @After fun close(): Unit = runBlocking {
        manager.activeNotifications.filter { it.tag?.contains(id.toString()) == true }.forEach { manager.cancel(it.tag,it.id) }
        withContext(Dispatchers.IO) { db.close() }; lease.close()
        context.deleteDatabase("subscriptions-$id.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$id.db") }
        java.io.File(context.filesDir,"datastore/settings-$id.preferences_pb").delete()
    }
    private fun runtime(hook: suspend () -> Unit = {}) = ReminderRuntime(context,scheduler,generation,publisher,{ now },{ zone },hook)
    private fun child(subId: UUID = sub.id) = manager.activeNotifications.singleOrNull { it.tag == ReminderScheduling.identity(id,subId) }
    private suspend fun await(label: String, test: () -> Boolean) {
        try { withTimeout(5_000) { while (!test()) delay(25) } }
        catch (e: TimeoutCancellationException) { throw AssertionError(label,e) }
    }
    private suspend fun published() {
        val run = runtime(); run.reconcile(); val task = scheduler.tasks.single()
        now = Instant.parse("2027-01-04T10:00:00Z")
        assertEquals(PublicationResult.PUBLISHED,run.process(id,sub.id,task.fingerprint))
        await("Native child exists") { child() != null }
    }
    @Test fun delayedMixedReasonsAreOneNativeMessageAndAllCoveredKeysAreAcknowledged(): Unit = runBlocking {
        val run = runtime(); run.reconcile(); val task = scheduler.tasks.single()
        now = Instant.parse("2027-01-04T10:00:00Z")
        assertEquals(PublicationResult.PUBLISHED,run.process(id,sub.id,task.fingerprint))
        await("One real mixed message") { child() != null }
        val text = child()!!.notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(text.contains("Завтра ожидается")); assertFalse(text.contains("Через 3"))
        assertFalse(text.contains("крайний срок"))
        val rows = db.deliveries().all()
        assertEquals(2,rows.size); assertTrue(rows.all { it.state == DeliveryState.DELIVERED })
        run.process(id,sub.id,task.fingerprint)
        assertEquals(1,manager.activeNotifications.count { it.tag == ReminderScheduling.identity(id,sub.id) })
    }
    @Test fun expiredManualPaymentAndDeadlineNeverCreateCatchUpNotification(): Unit = runBlocking {
        val run = runtime(); run.reconcile(); val task = scheduler.tasks.single()
        now = Instant.parse("2027-01-06T10:00:00Z")
        assertNull(run.process(id,sub.id,task.fingerprint)); assertNull(child())
        assertTrue(db.deliveries().all().all { it.state == DeliveryState.PENDING }); assertTrue(scheduler.tasks.isEmpty())
    }
    @Test fun nameChangeRemovesStaleNativeCopy(): Unit = runBlocking {
        published(); db.subscriptions().update(sub.copy(name = "Другое имя")); runtime().reconcile()
        await("Old name is removed") { child() == null }
    }
    @Test fun priceChangeRemovesStaleNativeCopy(): Unit = runBlocking {
        published(); db.subscriptions().update(sub.copy(priceMinor = 59900)); runtime().reconcile()
        await("Old price is removed") { child() == null }
    }
    @Test fun disablingGlobalDaysRemovesStaleNativeCopyWithoutResettingDeliveredKeys() = runBlocking {
        published(); val delivered = db.deliveries().all().map { it.id }.toSet()
        lease.repository.update { it.copy(renewalOffsets = emptySet()) }; runtime().reconcile()
        await("Disabled reminder is removed") { child() == null }
        assertEquals(delivered,db.deliveries().all().map { it.id }.toSet())
        assertTrue(db.deliveries().all().all { it.state == DeliveryState.DELIVERED })
    }

    @Test fun nextCalendarDayRemovesYesterdayCopy(): Unit = runBlocking {
        published(); now = Instant.parse("2027-01-05T10:00:00Z"); runtime().reconcile()
        await("Yesterday wording is removed") { child() == null }
    }
    @Test fun clearingOneStaleChildPreservesOtherChildAndSummary(): Unit = runBlocking {
        val second = sub.copy(id = UUID.randomUUID(),accountLabel = "Другой")
        db.subscriptions().insert(second)
        val run = runtime(); run.reconcile(); val tasks = scheduler.tasks.toList()
        now = Instant.parse("2027-01-04T10:00:00Z")
        tasks.forEach { run.process(id,it.subscription,it.fingerprint) }
        await("Both native children") { child() != null && child(second.id) != null }
        db.subscriptions().update(sub.copy(priceMinor = 59900)); run.reconcile()
        await("Only changed child removed") { child() == null && child(second.id) != null }
        assertTrue(manager.activeNotifications.any { it.tag == AndroidReminderPublisher.summaryTag(id) })
        db.subscriptions().delete(second.id); run.reconcile()
        await("No empty summary") { manager.activeNotifications.none { it.tag?.contains(id.toString()) == true } }
    }
    @Test fun simultaneousNativeWorkersUseOneClaimAndOneSystemIdentity(): Unit = runBlocking {
        val start = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = runtime { start.complete(Unit); release.await() }; first.reconcile()
        val task = scheduler.tasks.single(); now = Instant.parse("2027-01-04T10:00:00Z")
        val work = async(Dispatchers.IO) { first.process(id,sub.id,task.fingerprint) }
        withTimeout(10_000) { start.await() }
        assertNull(runtime().process(id,sub.id,task.fingerprint))
        release.complete(Unit); assertEquals(PublicationResult.PUBLISHED,work.await())
        await("One native child") { child() != null }
        assertEquals(1,manager.activeNotifications.count { it.tag == ReminderScheduling.identity(id,sub.id) })
        assertTrue(db.deliveries().all().all { it.state == DeliveryState.DELIVERED && it.claimToken == null })
    }
    @Test fun transactionalDateAndPriceEditCannotPublishPartialOrOldSnapshot(): Unit = runBlocking {
        val claimed = CompletableDeferred<Unit>(); val publish = CompletableDeferred<Unit>()
        val run = runtime { claimed.complete(Unit); publish.await() }; run.reconcile()
        val task = scheduler.tasks.single(); now = Instant.parse("2027-01-04T10:00:00Z")
        val work = async(Dispatchers.IO) { run.process(id,sub.id,task.fingerprint) }
        withTimeout(10_000) { claimed.await() }
        val halfWritten = CountDownLatch(1); val commit = CountDownLatch(1)
        val other = TrackerDatabaseFactory.open(context,id)
        val gate = ReminderChanges.gate(context.getDatabasePath("subscriptions-$id.db").canonicalPath)
        try {
            val writer = async(Dispatchers.IO) { gate.withLock {
                other.runInTransaction {
                    other.subscriptions().update(sub.copy(anchorDay = 9))
                    halfWritten.countDown()
                    check(commit.await(10,TimeUnit.SECONDS))
                    other.subscriptions().update(sub.copy(anchorDay = 9,priceMinor = 59900))
                }
            } }
            assertTrue(withContext(Dispatchers.IO) { halfWritten.await(10,TimeUnit.SECONDS) })
            publish.complete(Unit) // Worker proceeds while the transaction is still open, behind the shared gate.
            commit.countDown(); writer.await()
            assertNull(withTimeout(10_000) { work.await() }); assertNull(child())
            assertEquals(9,db.subscriptions().find(sub.id)!!.anchorDay)
            assertEquals(59900L,db.subscriptions().find(sub.id)!!.priceMinor)
            assertTrue(db.deliveries().all().none { it.state == DeliveryState.DELIVERED })
            assertTrue(db.deliveries().all().isNotEmpty())
            db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok",it.getString(0)) }
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { commit.countDown(); publish.complete(Unit); withContext(Dispatchers.IO) { other.close() } }
    }
    @Test fun concurrentThemeAndGlobalOffsetsRemainIndependent() = runBlocking {
        coroutineScope {
            launch { lease.repository.update { it.copy(renewalOffsets = setOf(7,3)) } }
            launch { lease.repository.update { it.copy(theme = com.glebskorobogatov.subscriptiontracker.data.settings.Theme.DARK) } }
        }
        runtime().reconcile()
        val settings = lease.repository.settings.first()
        assertEquals(setOf(7,3),settings.renewalOffsets)
        assertEquals(com.glebskorobogatov.subscriptiontracker.data.settings.Theme.DARK,settings.theme)
        val rows = db.deliveries().all()
        assertEquals(setOf(3),rows.map { it.offsetDays }.toSet()) // offset 7 is already past at initial planning.
        assertTrue(rows.all { it.eventType == ReminderEventType.RENEWAL })
    }

    @Test fun changingZoneBothDirectionsPreservesDateAndHandledKeys(): Unit = runBlocking {
        runtime().reconcile(); val original = db.deliveries().all()
        zone = ZoneId.of("Asia/Yekaterinburg"); runtime().reconcile()
        assertEquals(original.map { it.id }.toSet(),db.deliveries().all().map { it.id }.toSet())
        assertEquals(LocalDate.of(2027,1,5),db.subscriptions().find(sub.id)!!.let { LocalDate.of(it.anchorYear,it.anchorMonth,it.anchorDay) })
        assertEquals(LocalTime.of(9,0),scheduler.tasks.single().at.atZone(zone).toLocalTime())
        zone = ZoneId.of("Europe/Amsterdam"); runtime().reconcile()
        assertEquals(LocalTime.of(9,0),scheduler.tasks.single().at.atZone(zone).toLocalTime())
        published(); val handled = db.deliveries().all().map { it.id }.toSet()
        zone = ZoneId.of("Asia/Yekaterinburg"); runtime().reconcile()
        assertEquals(handled,db.deliveries().all().map { it.id }.toSet()); assertTrue(scheduler.tasks.isEmpty())
    }
    @Test fun globalRenewalsReconcileIdempotentlyAndEmptyDisablesThem() = runBlocking {
        repeat(3) { runtime().reconcile() }
        assertEquals(setOf(3,1),db.deliveries().all().map { it.offsetDays }.toSet())
        assertEquals(1,scheduler.tasks.size)
        assertEquals(Settings(),lease.repository.settings.first())
        lease.repository.update { it.copy(renewalOffsets = emptySet()) }; runtime().reconcile()
        assertTrue(scheduler.tasks.isEmpty()); assertTrue(db.deliveries().all().all { it.state == DeliveryState.PENDING })
    }

    @Test fun explicitDateEditWhileWorkerWaitsInvalidatesOldReminderWithoutPayment(): Unit = runBlocking {
        val claimed = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val run = runtime { claimed.complete(Unit); resume.await() }; run.reconcile()
        val task = scheduler.tasks.single(); now = Instant.parse("2027-01-04T10:00:00Z")
        val worker = async(Dispatchers.IO) { run.process(id,sub.id,task.fingerprint) }
        withTimeout(10_000) { claimed.await() }
        val cases = useCases()
        try {
            cases.editSubscription(sub,com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionEdit(name = sub.name, accountLabel = sub.accountLabel, price = RubPrice(sub.priceMinor), schedule = com.glebskorobogatov.subscriptiontracker.domain.subscription.ScheduleEdit(
                    LocalDate.of(2027,2,4),Frequency(FrequencyUnit.MONTH,1))),now)
            resume.complete(Unit); assertNull(withTimeout(10_000) { worker.await() }); assertNull(child())
            assertEquals(LocalDate.of(2027,2,4),db.subscriptions().find(sub.id)!!.toDomain().schedule.let { com.glebskorobogatov.subscriptiontracker.domain.calendar.DefaultRenewalCalendar().plannedCycleDate(it,0) })
            assertTrue(db.deliveries().all().filter { it.eventDate == LocalDate.of(2027,1,5) }.all { it.state == DeliveryState.PENDING })
        } finally { resume.complete(Unit) }
    }
    private fun useCases() = com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases(db,lease.repository,
        com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate(LocalDate.of(2027,1,4)),
        reminderChanges = ReminderChanges(ReminderChanges.gate(context.getDatabasePath("subscriptions-$id.db").canonicalPath)))

    @Test fun lifecycleWritesRemoveAndRestoreOnlyEligiblePendingReasons(): Unit = runBlocking {
        val cases = useCases(); val run = runtime()
        val automatic = sub.copy()
        db.subscriptions().update(automatic); run.reconcile(); assertTrue(scheduler.tasks.isNotEmpty())
        val cancelled = automatic.copy(state = SubscriptionState.CANCELLED)
        db.subscriptions().update(cancelled)
        run.reconcile(); assertTrue(scheduler.tasks.isEmpty()); assertNull(child())
        val archived = cases.changeLifecycle(cancelled,LifecycleCommand.Archive,now)
        run.reconcile(); assertTrue(scheduler.tasks.isEmpty())
        val restored = cases.changeLifecycle(archived,LifecycleCommand.Reactivate(RubPrice(sub.priceMinor), ScheduleEdit(LocalDate.of(2027,1,6),Frequency(FrequencyUnit.MONTH,1))),now)
        run.reconcile(); assertEquals(sub.id,restored.id); assertTrue(scheduler.tasks.isNotEmpty())
        assertTrue(scheduler.tasks.all { it.subscription == restored.id })
        assertTrue(db.deliveries().all().any { it.eventDate == LocalDate.of(2027,1,6) })
        val disabled = cases.changeLifecycle(restored,LifecycleCommand.Cancel(),now)
        run.reconcile(); assertTrue(scheduler.tasks.isEmpty())
        cases.deleteSubscription(disabled,true); run.reconcile()
        assertTrue(db.deliveries().all().all { it.state == DeliveryState.PENDING }); assertTrue(scheduler.tasks.isEmpty())
    }

    @Test fun realActiveDatasetGuardRejectsOldGenerationAfterAtomicPointerReplacement(): Unit = runBlocking {
        val directory = java.io.File(context.cacheDir,"f04-generation-$id").apply { mkdirs() }
        val active = com.glebskorobogatov.subscriptiontracker.app.ActiveDataset(directory)
        val old = active.getOrCreate(); val replacement = UUID.randomUUID()
        val replacing = CountDownLatch(1); val finish = CountDownLatch(1)
        try {
            val switch = async(Dispatchers.IO) { active.ifCurrent(old) {
                replacing.countDown(); check(finish.await(10,TimeUnit.SECONDS))
                val pointer = android.util.AtomicFile(java.io.File(directory,"active-generation.pointer"))
                val output = pointer.startWrite()
                try { output.write(replacement.toString().toByteArray()); pointer.finishWrite(output) }
                catch (error: Exception) { pointer.failWrite(output); throw error }
            } }
            assertTrue(withContext(Dispatchers.IO) { replacing.await(10,TimeUnit.SECONDS) })
            var sideEffects = 0
            val stale = async(Dispatchers.IO) { active.ifCurrent(old) { sideEffects++; true } }
            finish.countDown(); switch.await()
            assertNull(stale.await()); assertEquals(0,sideEffects)
            assertEquals(replacement,active.getOrCreate()); assertEquals(true,active.ifCurrent(replacement) { true })
        } finally { finish.countDown(); directory.deleteRecursively() }
    }

}
