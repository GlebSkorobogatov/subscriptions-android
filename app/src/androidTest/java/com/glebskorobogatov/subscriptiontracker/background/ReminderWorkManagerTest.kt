package com.glebskorobogatov.subscriptiontracker.background

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.*
import com.glebskorobogatov.subscriptiontracker.data.reminders.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class ReminderWorkManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: WorkManager
    private lateinit var scheduler: WorkManagerReminderScheduler
    private val now = Instant.parse("2027-01-01T00:00:00Z")
    private lateinit var dataset: UUID
    private lateinit var access: ReminderInfrastructureTest.TestGeneration
    private lateinit var factory: WorkerFactory
    private var blocked: CompletableDeferred<Unit>? = null
    private val entered = CompletableDeferred<Unit>()
    private lateinit var records: ReminderInfrastructureTest.RecordingScheduler
    @Before fun initialize() {
        dataset = UUID.randomUUID(); com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context,dataset); records = ReminderInfrastructureTest.RecordingScheduler()
        access = ReminderInfrastructureTest.TestGeneration(dataset)
        factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName != ReminderWorker::class.java.name) null
                else blocked?.let { barrier -> object : CoroutineWorker(appContext,workerParameters) {
                    override suspend fun doWork(): Result { entered.complete(Unit); barrier.await(); return Result.success() }
                } } ?: ReminderWorker(appContext,workerParameters,
                    ReminderRuntime(appContext,records,access,now = { now },zone = { ZoneOffset.UTC }))
        }
        // Executors belong to WorkManager/the coroutine runtime, not an individual test method.
        val config = Configuration.Builder().setWorkerFactory(factory).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,config,
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS)
        manager = WorkManager.getInstance(context)
        scheduler = WorkManagerReminderScheduler(context, activeGeneration = access::current) { now }
    }
    @After fun close() {
        manager.cancelAllWork().result.get(10,TimeUnit.SECONDS)
        // Drain cancellation/cleanup on the serial task queue before closing the WorkManager scope.
        manager.pruneWork().result.get(10,TimeUnit.SECONDS)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        WorkManagerTestInitHelper.closeWorkDatabase()
        context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }
        java.io.File(context.filesDir,"datastore/settings-$dataset.preferences_pb").delete()
    }
    private fun pending(tag: String) = manager.getWorkInfosByTag(tag).get(10,TimeUnit.SECONDS).filter { !it.state.isFinished }
    private fun task(at: Instant = now.plusSeconds(3600)) = ScheduledReminder(UUID(0,1),"a".repeat(64),at)
    @Test fun oneTimeRequestIsDurableAndRepeatedReconciliationKeepsSameRequest(): Unit = runBlocking {
        val plan = task()
        repeat(3) { scheduler.apply(dataset,listOf(plan),now.plusSeconds(86400)) }
        val works = pending(WorkManagerReminderScheduler.DELIVERY)
        assertEquals(1,works.size); assertEquals(WorkInfo.State.ENQUEUED,works.single().state)
        assertEquals(1,pending(WorkManagerReminderScheduler.WAKE).size)
    }
    @Test fun obsoleteTaskIsCancelledAndNewSlotIsKept(): Unit = runBlocking {
        scheduler.apply(dataset,listOf(task()),now.plusSeconds(86400))
        val old = pending(WorkManagerReminderScheduler.DELIVERY).single().id
        scheduler.apply(dataset,listOf(task().copy(fingerprint = "b".repeat(64))),now.plusSeconds(86400))
        assertEquals(WorkInfo.State.CANCELLED,requireNotNull(manager.getWorkInfoById(old).get()).state)
        assertEquals(1,pending(WorkManagerReminderScheduler.DELIVERY).size)
    }
    @Test fun deletingPlanCancelsDeliveryWithoutLeavingInfiniteQueue(): Unit = runBlocking {
        scheduler.apply(dataset,listOf(task()),now.plusSeconds(86400))
        repeat(3) { scheduler.apply(dataset,emptyList(),now.plusSeconds(86400)) }
        assertTrue(pending(WorkManagerReminderScheduler.DELIVERY).isEmpty())
        assertEquals(1,pending(WorkManagerReminderScheduler.WAKE).size)
    }
    @Test fun officialTestDriverReleasesDelayedWorkWithoutRealWaiting(): Unit = runBlocking {
        scheduler.apply(dataset,listOf(task()),now.plusSeconds(86400))
        val work = pending(WorkManagerReminderScheduler.DELIVERY).single()
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        driver.setInitialDelayMet(work.id)
        // Await an observable WorkManager state rather than Thread.sleep.
        val result = manager.getWorkInfoByIdFlow(work.id).firstFinished()
        assertEquals(WorkInfo.State.SUCCEEDED,result.state)
    }
    @Test fun workerRejectsCorruptPayloadRatherThanReportingSuccessfulDelivery(): Unit = runBlocking {
        val worker = TestListenableWorkerBuilder<ReminderWorker>(context).setWorkerFactory(factory)
            .setInputData(workDataOf("version" to 99)).build()
        assertEquals(ListenableWorker.Result.failure(),worker.doWork())
        assertFalse(context.getDatabasePath("subscriptions-$dataset.db").exists())
    }
    @Test fun workerWithoutActivityCanReconcileCurrentDataset(): Unit = runBlocking {
        val worker = TestListenableWorkerBuilder<ReminderWorker>(context).setWorkerFactory(factory)
            .setInputData(ReminderWorkInput.data(dataset)).build()
        assertEquals(ListenableWorker.Result.success(),worker.doWork())
        assertTrue(context.getDatabasePath("subscriptions-$dataset.db").exists())
        assertTrue(records.tasks.isEmpty()); assertNotNull(records.wake)
    }
    @Test fun oldGenerationWorkerCompletesWithoutOpeningOldOrCurrentDatabase(): Unit = runBlocking {
        val old = UUID.randomUUID()
        for (input in listOf(ReminderWorkInput.data(old,UUID(0,1),"a".repeat(64)),
            ReminderWorkInput.reconciliation(old))) {
            val worker = TestListenableWorkerBuilder<ReminderWorker>(context).setWorkerFactory(factory)
                .setInputData(input).build()
            assertEquals(ListenableWorker.Result.success(),worker.doWork())
            assertFalse(context.getDatabasePath("subscriptions-$old.db").exists())
            assertFalse(context.getDatabasePath("subscriptions-$dataset.db").exists())
        }
        assertEquals(2,records.requests)
    }
    @Test fun payloadFitsDataLimitAndRejectsNonCanonicalGeneration() {
        val payload = ReminderWorkInput.data(dataset,UUID(0,1),"a".repeat(64))
        assertTrue(payload.toByteArray().size < 1024)
        assertEquals(dataset,ReminderWorkInput.generation(payload))
        assertEquals(dataset,ReminderWorkInput.generation(ReminderWorkInput.reconciliation(dataset)))
        assertThrows(IllegalArgumentException::class.java) {
            ReminderWorkInput.generation(workDataOf("version" to 1,"generation" to "1-1-1-1-1"))
        }
    }
    @Test fun futureSuccessorIsNotSwallowedByKeepWhilePriorWorkerIsRunning(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>(); blocked = release
        scheduler.apply(dataset,listOf(task()),now.plusSeconds(86400))
        val original = pending(WorkManagerReminderScheduler.DELIVERY).single()
        requireNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setInitialDelayMet(original.id)
        kotlinx.coroutines.withTimeout(10_000) { entered.await() }
        repeat(2) { scheduler.apply(dataset,listOf(task()),now.plusSeconds(86400)) }
        val active = pending(WorkManagerReminderScheduler.DELIVERY)
        assertEquals(1,active.count { it.state == WorkInfo.State.RUNNING })
        assertEquals(1,active.count { it.state == WorkInfo.State.BLOCKED })
        release.complete(Unit)
        manager.getWorkInfoByIdFlow(original.id).firstFinished()
    }
    @Test fun systemClockResetReplacesExistingMonotonicDelayAfterDurableReconcileRequest(): Unit = runBlocking {
        scheduler.apply(dataset,listOf(task()),now.plusSeconds(86400))
        val old = pending(WorkManagerReminderScheduler.DELIVERY).single().id
        val fallbackWake = pending(WorkManagerReminderScheduler.WAKE).single().id
        scheduler.rescheduleAfterClockChange()
        assertEquals(fallbackWake,pending(WorkManagerReminderScheduler.WAKE).single().id)
        assertEquals(WorkInfo.State.CANCELLED,requireNotNull(manager.getWorkInfoById(old).get()).state)
        assertTrue(manager.getWorkInfosByTag(WorkManagerReminderScheduler.RECONCILE).get().isNotEmpty())
        scheduler.apply(dataset,listOf(task()),now.plusSeconds(86400))
        assertNotEquals(old,pending(WorkManagerReminderScheduler.DELIVERY).single().id)
    }
    @Test fun outdatedGenerationCannotCancelCurrentGenerationRequests(): Unit = runBlocking {
        scheduler.apply(dataset,listOf(task()),now.plusSeconds(86400))
        val replacement = UUID.randomUUID(); access.id = replacement
        scheduler.apply(replacement,listOf(task()),now.plusSeconds(86400))
        val current = pending(WorkManagerReminderScheduler.ROOT).map { it.id }.toSet()
        scheduler.apply(dataset,emptyList(),now.plusSeconds(86400))
        assertEquals(current,pending(WorkManagerReminderScheduler.ROOT).map { it.id }.toSet())
        assertEquals(1,pending(WorkManagerReminderScheduler.DELIVERY).size)
    }
    private suspend fun kotlinx.coroutines.flow.Flow<WorkInfo?>.firstFinished(): WorkInfo =
        requireNotNull(kotlinx.coroutines.withTimeout(10_000) { this@firstFinished.first { it?.state?.isFinished == true } })
    @Test fun createThroughUseCasesEnqueuesRealControlWorkerWithoutActivity(): Unit = runBlocking {
        val db = TrackerDatabaseFactory.open(context,dataset)
        val lease = com.glebskorobogatov.subscriptiontracker.data.settings.DatasetSettingsFactory.acquire(context,dataset)
        try {
            val cases = com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases(db,lease.repository,
                com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate(LocalDate.of(2027,1,1)),
                reminderChanges = ReminderChanges(ReminderChanges.gate(context.getDatabasePath("subscriptions-$dataset.db").canonicalPath)) {
                    scheduler.requestReconcile()
                })
            repeat(2) {
                cases.createSubscription(com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionDraft(id = UUID.randomUUID(), name = "Одинаковое имя", accountLabel = "Тест", price = RubPrice(39900), schedule = ScheduleEdit(LocalDate.of(2027,1,5), Frequency(FrequencyUnit.MONTH,1))),now)
            }
            val controls = manager.getWorkInfosForUniqueWork(WorkManagerReminderScheduler.RECONCILE).get(10,TimeUnit.SECONDS)
            assertTrue(controls.isNotEmpty())
            controls.forEach { manager.getWorkInfoByIdFlow(it.id).firstFinished() }
            // A final deterministic reconcile sees both committed rows, even if first work ran between saves.
            ReminderRuntime(context,records,access,now = { now },zone = { ZoneOffset.UTC }).reconcile()
            assertEquals(2,records.tasks.size); assertEquals(2,records.tasks.map { it.subscription }.toSet().size)
            assertTrue(db.deliveries().all().none { it.state == DeliveryState.DELIVERED })
        } finally { db.close(); lease.close() }
    }

    @Test fun legacyDeliveryControlAndWakeFinishWithoutOpeningStorage() = runBlocking {
        val originalRequests = records.requests
        val payloads = listOf(
            workDataOf("version" to 1,"generation" to dataset.toString(),"subscription" to UUID.randomUUID().toString(),"fingerprint" to "a".repeat(64)),
            workDataOf("version" to 1,"generation" to dataset.toString(),"reconcile" to true),
            workDataOf("version" to 1,"generation" to dataset.toString()),
            Data.Builder().putAll(ReminderWorkInput.data(dataset)).putInt("policy",2).build())
        for (payload in payloads) {
            val worker = TestListenableWorkerBuilder<ReminderWorker>(context).setWorkerFactory(factory).setInputData(payload).build()
            assertEquals(ListenableWorker.Result.success(),worker.doWork())
            assertFalse(context.getDatabasePath("subscriptions-$dataset.db").exists())
        }
        assertEquals(originalRequests+4,records.requests)
        assertTrue(records.tasks.isEmpty()); assertNull(records.wake)
    }
    @Test fun futureUnknownPolicyFailsWithoutOpeningOrRequestingWork() = runBlocking {
        val data = Data.Builder().putAll(ReminderWorkInput.data(dataset)).putInt("policy",99).build()
        val worker = TestListenableWorkerBuilder<ReminderWorker>(context).setWorkerFactory(factory).setInputData(data).build()
        assertEquals(ListenableWorker.Result.failure(),worker.doWork())
        assertFalse(context.getDatabasePath("subscriptions-$dataset.db").exists()); assertEquals(0,records.requests)
    }
    @Test fun retireOnlyLegacyReminderQueueAndKeepUnrelatedWork() = runBlocking {
        val old = OneTimeWorkRequestBuilder<ReminderWorker>().addTag(WorkManagerReminderScheduler.ROOT)
            .addTag(WorkManagerReminderScheduler.DELIVERY).setInitialDelay(1,TimeUnit.DAYS)
            .setInputData(workDataOf("version" to 1,"generation" to dataset.toString())).build()
        val unrelated = OneTimeWorkRequestBuilder<ReminderWorker>().addTag("synthetic-unrelated-work")
            .setInitialDelay(1,TimeUnit.DAYS).setInputData(ReminderWorkInput.data(dataset)).build()
        manager.enqueue(listOf(old,unrelated)).result.get(10,TimeUnit.SECONDS)
        scheduler.requestReconcile()
        assertEquals(WorkInfo.State.CANCELLED,requireNotNull(manager.getWorkInfoById(old.id).get()).state)
        assertEquals(WorkInfo.State.ENQUEUED,requireNotNull(manager.getWorkInfoById(unrelated.id).get()).state)
        val current = manager.getWorkInfosForUniqueWork(WorkManagerReminderScheduler.RECONCILE).get()
        assertTrue(current.any { ReminderWorkInput.POLICY_TAG in it.tags })
    }

}
