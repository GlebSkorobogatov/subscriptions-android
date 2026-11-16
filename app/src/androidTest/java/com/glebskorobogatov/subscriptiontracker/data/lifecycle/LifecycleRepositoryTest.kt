package com.glebskorobogatov.subscriptiontracker.data.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.payment.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.payment.*
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
    private fun payment(s: SubscriptionEntity) = PaymentCommand(OperationId.random(), SubscriptionId(s.id), day,
        Money(200, s.currencyCode), PaymentSource.CONFIRMED_RENEWAL, true, s.toDomain().schedule, s.renewalMode,
        day.plusMonths(1), LifecycleState.PAYMENT_ISSUE)
    @Test fun lifeT06T07IssuePaymentAtomicIdempotentAndConflictingRepeatRejected() = runBlocking {
        val s = row(); insert(s)
        val issue = repo.execute(s, LifecycleCommand.MarkPaymentIssue, now)
        withContext(Dispatchers.IO) { assertTrue(db.payments().all().isEmpty()) }
        val command = payment(issue); val payments = PaymentRepository(db)
        val results = coroutineScope { (1..4).map { async { payments.execute(command, day, now) } }.awaitAll() }
        assertEquals(1, results.distinct().size)
        assertEquals(LifecycleState.ACTIVE, read(s).lifecycleState); assertEquals(1L, read(s).nextCycleIndex)
        try { payments.execute(command.copy(updateFuturePrice = false), day, now); fail() } catch (_: PaymentConflict) { }
        withContext(Dispatchers.IO) { assertEquals(1, db.payments().all().size) }
    }
    @Test fun lifeT08T09ThreeResolutionPathsKeepHistoryForNonPaymentActions() = runBlocking {
        val s = row(); insert(s)
        val issue = repo.execute(s, LifecycleCommand.MarkPaymentIssue, now)
        val moved = repo.execute(issue, LifecycleCommand.ResolveWithDate(day.plusDays(2)), now)
        assertEquals(LifecycleState.ACTIVE, moved.lifecycleState); assertEquals(day.plusDays(2), moved.oneOffDueDate)
        val issueAgain = repo.execute(moved, LifecycleCommand.MarkPaymentIssue, now)
        assertEquals(LifecycleState.ARCHIVED, repo.execute(issueAgain, LifecycleCommand.Archive, now).lifecycleState)
        withContext(Dispatchers.IO) { assertTrue(db.payments().all().isEmpty()) }
    }
    @Test fun lifeT10T12KnownUnknownAndRepeatAutoArchive() = runBlocking {
        val a = row(); val b = row(); insert(a); insert(b)
        repo.execute(a, LifecycleCommand.DisableRenewal(day), now)
        repo.execute(b, LifecycleCommand.DisableRenewal(), now)
        assertEquals(0, repo.autoArchiveExpired(day, now))
        assertEquals(1, repo.autoArchiveExpired(day.plusDays(1), now.plusSeconds(1)))
        val archived = read(a)
        assertEquals(0, repo.autoArchiveExpired(day.plusDays(2), now.plusSeconds(2)))
        assertEquals(archived, read(a)); assertEquals(LifecycleState.CANCELLATION_SCHEDULED, read(b).lifecycleState)
    }
    @Test fun lifeT13T16ReactivationPreservesOldPaymentAndRefundCurrency() = runBlocking {
        val a = row(); val b = row(); insert(a); insert(b)
        val p = PaymentEntity(UUID.randomUUID(), a.id, UUID.randomUUID(), day, 100, a.currencyCode, now, PaymentSource.MANUAL_HISTORY)
        val r = RefundEntity(UUID.randomUUID(), p.id, day, 10, a.currencyCode)
        withContext(Dispatchers.IO) { db.payments().insert(p); db.refunds().insert(r) }
        val archived = repo.execute(a, LifecycleCommand.Archive, now)
        val active = repo.execute(archived, LifecycleCommand.Reactivate(day.plusDays(3), Frequency(FrequencyUnit.YEAR, 1), RenewalMode.MANUAL, Money(900, CurrencyCode.of("USD"))), now)
        assertEquals("USD", active.currencyCode.value); assertEquals(b, read(b))
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
        val outcomes = coroutineScope { listOf(LifecycleCommand.MarkPaymentIssue, LifecycleCommand.Archive).map { command ->
            async { try { repo.execute(s, command, now); true } catch (_: LifecycleConflict) { false } }
        }.awaitAll() }
        assertEquals(1, outcomes.count { it })
        try { repo.delete(s, true); fail() } catch (_: LifecycleConflict) { }
    }
    @Test fun failedPaymentRecoveryRollsBackStateAndHistory() = runBlocking {
        val s = row(); insert(s); val issue = repo.execute(s, LifecycleCommand.MarkPaymentIssue, now)
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON subscriptions BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        try { PaymentRepository(db).execute(payment(issue), day, now); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(issue, read(s)); withContext(Dispatchers.IO) { assertTrue(db.payments().all().isEmpty()) }
    }
    @Test fun failedBulkArchiveRollsBackAllRows() = runBlocking {
        val a = row(); val b = row(); insert(a); insert(b)
        val ca = repo.execute(a, LifecycleCommand.DisableRenewal(day), now); val cb = repo.execute(b, LifecycleCommand.DisableRenewal(day), now)
        withContext(Dispatchers.IO) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_second BEFORE UPDATE ON subscriptions WHEN (SELECT count(*) FROM subscriptions WHERE lifecycleState = 'ARCHIVED') > 0 BEGIN SELECT RAISE(ABORT, 'injected'); END")
        }
        try { repo.autoArchiveExpired(day.plusDays(1), now); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(ca, read(a)); assertEquals(cb, read(b))
    }
}
