package com.glebskorobogatov.subscriptiontracker.data.refund

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.payment.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.payment.*
import com.glebskorobogatov.subscriptiontracker.domain.refund.*
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefundRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: TrackerDatabase
    private lateinit var repo: RefundRepository
    private lateinit var dataset: UUID
    private val day = LocalDate.of(2027, 11, 10)
    private val now = Instant.parse("2027-11-10T12:00:00Z")
    private val rub = CurrencyCode.of("RUB")
    @Before fun open() { dataset = UUID.randomUUID(); db = TrackerDatabaseFactory.open(context, dataset); repo = RefundRepository(db) }
    @After fun close() { db.close(); context.deleteDatabase("subscriptions-$dataset.db") }
    private suspend fun setup(): Pair<SubscriptionEntity, PaymentEntity> = withContext(Dispatchers.IO) {
        val s = SubscriptionEntity(UUID.randomUUID(), "Тест", null, 999, rub, FrequencyUnit.MONTH, 1,
            RenewalMode.AUTO, LifecycleState.ACTIVE, 2027, 11, 10, 0, null, null, null, null, null, null, null, now, now)
        db.subscriptions().insert(s)
        val p = PaymentEntity(UUID.randomUUID(), s.id, UUID.randomUUID(), day.minusDays(1), 999, rub, now, PaymentSource.MANUAL_HISTORY)
        db.payments().insert(p); s to p
    }
    private fun refund(p: PaymentEntity, amount: Long) = RefundEntity(UUID.randomUUID(), p.id, day, amount, rub)
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: HistoryConflict) { }
    }
    @Test fun moneyT08T10PartialFullAndExtraUnitAtomic() = runBlocking {
        val (_, p) = setup(); repo.addRefund(refund(p, 300), day); repo.addRefund(refund(p, 200), day)
        assertEquals(499L, repo.netExpense(p.id, day).amountMinor)
        repo.addRefund(refund(p, 499), day); assertEquals(0L, repo.netExpense(p.id, day).amountMinor)
        rejected { repo.addRefund(refund(p, 1), day) }
        withContext(Dispatchers.IO) { assertEquals(3, db.refunds().forPayment(p.id).size); assertEquals(p, db.payments().find(p.id)) }
    }
    @Test fun uuidRetryConflictMissingParentAndWrongCurrency() = runBlocking {
        val (_, p) = setup(); val r = refund(p, 300)
        assertEquals(r, repo.addRefund(r, day)); assertEquals(r, repo.addRefund(r, day))
        rejected { repo.addRefund(r.copy(amountMinor = 301), day) }
        rejected { repo.addRefund(refund(p, 1).copy(paymentId = UUID.randomUUID()), day) }
        rejected { repo.addRefund(refund(p, 1).copy(currencyCode = CurrencyCode.of("USD")), day) }
        withContext(Dispatchers.IO) { assertEquals(listOf(r), db.refunds().forPayment(p.id)) }
    }
    @Test fun moneyT11T13EditDeleteAndStaleSnapshots() = runBlocking {
        val (_, p) = setup(); val a = repo.addRefund(refund(p, 300), day); val b = repo.addRefund(refund(p, 200), day)
        val changed = repo.editRefund(a, day, Money(400, rub), day)
        rejected { repo.editRefund(a, day, Money(350, rub), day) }
        rejected { repo.editRefund(changed, day, Money(800, rub), day) }
        rejected { repo.editPayment(p, p.paidOn, Money(599, rub), day) }
        rejected { repo.editPayment(p, p.paidOn, Money(999, CurrencyCode.of("USD")), day) }
        assertEquals(799L, repo.deleteRefund(changed, day).amountMinor)
        rejected { repo.addRefund(changed, day) }
        rejected { repo.addRefund(a, day) }
        val edited = repo.editPayment(p, day, Money(800, rub), day)
        assertEquals(p.operationId, edited.operationId); assertEquals(p.requestPayload, edited.requestPayload)
        rejected { repo.editPayment(p, day, Money(900, rub), day) }
        withContext(Dispatchers.IO) { assertEquals(listOf(b), db.refunds().forPayment(p.id)) }
    }
    @Test fun historyChangesNeverChangeSubscriptionOrForecast() = runBlocking {
        val (s, p) = setup(); val before = ForecastEngine().calendarMonths(listOf(s.toDomain()), day, 3)
        val r = repo.addRefund(refund(p, 300), day)
        repo.editRefund(r, day, Money(200, rub), day)
        repo.editPayment(p, p.paidOn, Money(700, rub), day)
        val after = withContext(Dispatchers.IO) { db.subscriptions().find(s.id)!! }
        assertEquals(s, after); assertEquals(before, ForecastEngine().calendarMonths(listOf(after.toDomain()), day, 3))
    }
    @Test fun moneyT14DeleteCascadeConfirmationAndOtherRecords() = runBlocking {
        val (s, p) = setup(); val (_, other) = setup()
        repo.addRefund(refund(p, 300), day); val otherRefund = repo.addRefund(refund(other, 20), day)
        rejected { repo.deletePayment(p, false) }
        repo.deletePayment(p, true)
        withContext(Dispatchers.IO) {
            assertNull(db.payments().find(p.id)); assertTrue(db.refunds().forPayment(p.id).isEmpty())
            assertEquals(other, db.payments().find(other.id)); assertEquals(otherRefund, db.refunds().find(otherRefund.id))
            assertEquals(s, db.subscriptions().find(s.id)); assertTrue(db.retiredPaymentOperations().contains(p.operationId))
        }
    }
    @Test fun deletedOperationCannotRenewAgainAfterReopenEvenIfScheduleRestored() = runBlocking {
        val (s, _) = setup()
        val command = PaymentCommand(OperationId.random(), SubscriptionId(s.id), day, Money(999, rub), PaymentSource.CONFIRMED_RENEWAL,
            expectedSchedule = s.toDomain().schedule, expectedMode = s.renewalMode)
        val payment = PaymentRepository(db).execute(command, day, now)
        repo.addRefund(refund(payment, 10), day); repo.deletePayment(payment, true)
        withContext(Dispatchers.IO) { db.subscriptions().update(s) } // prove protection is not just a schedule mismatch
        db.close(); db = TrackerDatabaseFactory.open(context, dataset); repo = RefundRepository(db)
        try { PaymentRepository(db).execute(command, day, now); fail() } catch (_: PaymentConflict) { }
        withContext(Dispatchers.IO) { assertEquals(s, db.subscriptions().find(s.id)); assertNull(db.payments().findByOperation(command.operationId.value)) }
    }
    @Test fun monetaryHistorySurvivesReopen() = runBlocking {
        val (_, p) = setup(); val r = repo.addRefund(refund(p, 300), day)
        val updated = repo.editPayment(p, p.paidOn, Money(800, rub), day)
        db.close(); db = TrackerDatabaseFactory.open(context, dataset); repo = RefundRepository(db)
        withContext(Dispatchers.IO) { assertEquals(updated, db.payments().find(p.id)); assertEquals(r, db.refunds().find(r.id)) }
        assertEquals(500L, repo.netExpense(p.id, day).amountMinor)
    }
    @Test fun competingRefundsCannotExceedPayment() = runBlocking {
        val (_, p) = setup()
        val result = coroutineScope { (1..2).map { async {
            try { repo.addRefund(refund(p, 600), day); true } catch (_: IllegalArgumentException) { false }
        } }.awaitAll() }
        assertEquals(1, result.count { it }); assertEquals(399L, repo.netExpense(p.id, day).amountMinor)
    }
    @Test fun sqlFailureRollsBackDeletionAndTombstone() = runBlocking {
        val (_, p) = setup(); val r = repo.addRefund(refund(p, 100), day)
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON refunds BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        try { repo.deletePayment(p, true); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        withContext(Dispatchers.IO) {
            assertEquals(p, db.payments().find(p.id)); assertEquals(r, db.refunds().find(r.id))
            assertFalse(db.retiredPaymentOperations().contains(p.operationId))
        }
    }
    @Test fun invalidDatesAndFailedEditLeaveStoredHistoryIntact() = runBlocking {
        val (_, p) = setup(); val r = repo.addRefund(refund(p, 100), day)
        rejected { repo.addRefund(refund(p, 1).copy(refundedOn = p.paidOn.minusDays(1)), day) }
        rejected { repo.addRefund(refund(p, 1).copy(refundedOn = day.plusDays(1)), day) }
        rejected { repo.editPayment(p, day.plusDays(1), Money(999, rub), day) }
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_refund_edit BEFORE UPDATE ON refunds BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        try { repo.editRefund(r, day, Money(200, rub), day); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        withContext(Dispatchers.IO) { assertEquals(r, db.refunds().find(r.id)); assertEquals(p, db.payments().find(p.id)) }
    }
    @Test fun subscriptionCascadeAlsoRetiresOperationIds() = runBlocking {
        val (s, p) = setup()
        com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleRepository(db).delete(s, true)
        withContext(Dispatchers.IO) {
            assertNull(db.payments().find(p.id)); assertTrue(db.retiredPaymentOperations().contains(p.operationId))
        }
    }

}
