package com.glebskorobogatov.subscriptiontracker.data.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifecycleRepositoryTest {
    private lateinit var db: TrackerDatabase
    private lateinit var repo: LifecycleRepository
    private val day = LocalDate.of(2027, 11, 10)
    private val now = Instant.parse("2027-11-10T12:00:00Z")
    private fun row() = SubscriptionEntity(UUID.randomUUID(), "Одинаковый сервис", null, 100, CurrencyCode.of("RUB"),
        FrequencyUnit.MONTH, 1, RenewalMode.AUTO, LifecycleState.ACTIVE, 2027, 11, 10, 0,
        null, null, null, null, null, null, null, now, now)
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrackerDatabase::class.java).build(); repo = LifecycleRepository(db) }
    @After fun close() { db.close() }
    private suspend fun insert(s: SubscriptionEntity) = withContext(Dispatchers.IO) { db.subscriptions().insert(s) }
    private suspend fun read(s: SubscriptionEntity) = withContext(Dispatchers.IO) { db.subscriptions().find(s.id)!! }
    @Test fun existingIssueResolutionIsAtomicAndConcurrentStaleCommandsAreRejected() = runBlocking {
        val issue = row().copy(lifecycleState = LifecycleState.PAYMENT_ISSUE); insert(issue)
        val results = coroutineScope { (1..4).map { async {
            try { repo.execute(issue,LifecycleCommand.ResolveWithDate(day.plusDays(2)),now); true }
            catch (_: LifecycleConflict) { false }
        } }.awaitAll() }
        assertEquals(1,results.count { it }); assertEquals(LifecycleState.ACTIVE,read(issue).lifecycleState)
        assertEquals(0L,read(issue).nextCycleIndex)
        withContext(Dispatchers.IO) { assertTrue(db.payments().all().isEmpty()) }
    }
    @Test fun lifeT08T09ThreeResolutionPathsKeepHistoryForNonPaymentActions() = runBlocking {
        val s = row().copy(lifecycleState = LifecycleState.PAYMENT_ISSUE); insert(s)
        val issue = s
        val moved = repo.execute(issue, LifecycleCommand.ResolveWithDate(day.plusDays(2)), now)
        assertEquals(LifecycleState.ACTIVE, moved.lifecycleState); assertEquals(day.plusDays(2), moved.oneOffDueDate)
        val issueAgain = moved.copy(lifecycleState = LifecycleState.PAYMENT_ISSUE)
        withContext(Dispatchers.IO) { db.subscriptions().update(issueAgain) }
        assertEquals(LifecycleState.ARCHIVED, repo.execute(issueAgain, LifecycleCommand.Archive, now).lifecycleState)
        withContext(Dispatchers.IO) { assertTrue(db.payments().all().isEmpty()) }
    }

    @Test fun lifeT13T16ReactivationPreservesOldPaymentAndRefundCurrency() = runBlocking {
        val a = row(); val b = row(); insert(a); insert(b)
        val p = PaymentEntity(UUID.randomUUID(), a.id, UUID.randomUUID(), day, 100, a.currencyCode, now, PaymentSource.MANUAL_HISTORY)
        val r = RefundEntity(UUID.randomUUID(), p.id, day, 10, a.currencyCode)
        withContext(Dispatchers.IO) { db.payments().insert(p); db.refunds().insert(r) }
        val archived = repo.execute(a, LifecycleCommand.Archive, now)
        val active = repo.execute(archived, LifecycleCommand.Reactivate(day.plusDays(3), Frequency(FrequencyUnit.YEAR, 1), RenewalMode.MANUAL, Money(900, a.currencyCode)), now)
        assertEquals(a.currencyCode, active.currencyCode); assertEquals(b, read(b))
        withContext(Dispatchers.IO) { assertEquals(p, db.payments().find(p.id)); assertEquals(r, db.refunds().find(r.id)) }
    }
    @Test fun lifeT14T15CascadeDeletionRequiresConfirmationAndRetainsOtherRecords() = runBlocking {
        val a = row(); val b = row(); insert(a); insert(b)
        val p = PaymentEntity(UUID.randomUUID(), a.id, UUID.randomUUID(), day, 100, a.currencyCode, now, PaymentSource.MANUAL_HISTORY)
        withContext(Dispatchers.IO) {
            db.payments().insert(p); db.refunds().insert(RefundEntity(UUID.randomUUID(), p.id, day, 10, a.currencyCode))
            db.reminders().insert(ReminderOverrideEntity(a.id, ReminderEventType.PAYMENT, ReminderMode.CUSTOM, setOf(0)))
            db.deliveries().insert(NotificationDeliveryEntity(UUID.randomUUID(), a.id, ReminderEventType.PAYMENT, day, 0, day, now, DeliveryState.DELIVERED, UUID.randomUUID()))
        }
        try { repo.delete(a, false); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(a, read(a)); repo.delete(a, true)
        withContext(Dispatchers.IO) {
            assertNull(db.subscriptions().find(a.id)); assertEquals(b, db.subscriptions().find(b.id))
            assertTrue(db.payments().all().isEmpty()); assertTrue(db.refunds().all().isEmpty())
            assertTrue(db.reminders().forSubscription(a.id).isEmpty()); assertTrue(db.deliveries().all().isEmpty())
        }
    }
    @Test fun lifeT17ConcurrentCommandsAndStaleDeletionCannotOverwrite() = runBlocking {
        val s = row(); insert(s)
        val outcomes = coroutineScope { listOf(LifecycleCommand.DisableRenewal(day), LifecycleCommand.Archive).map { command ->
            async { try { repo.execute(s, command, now); true } catch (_: LifecycleConflict) { false } }
        }.awaitAll() }
        assertEquals(1, outcomes.count { it })
        try { repo.delete(s, true); fail() } catch (_: LifecycleConflict) { }
    }
    @Test fun failedLegacyDateResolutionRollsBackStateAndHistory() = runBlocking {
        val issue = row().copy(lifecycleState = LifecycleState.PAYMENT_ISSUE); insert(issue)
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON subscriptions BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        try { repo.execute(issue,LifecycleCommand.ResolveWithDate(day.plusDays(2)),now); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(issue,read(issue)); withContext(Dispatchers.IO) { assertTrue(db.payments().all().isEmpty()) }
    }

}
