package com.glebskorobogatov.subscriptiontracker.data.refund

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyRefundStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: TrackerDatabase
    private lateinit var dataset: UUID
    private val day = LocalDate.of(2027, 11, 10)
    private val now = Instant.parse("2027-11-10T12:00:00Z")
    private val rub = CurrencyCode.of("RUB")
    @Before fun open() { dataset = UUID.randomUUID(); com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset); db = TrackerDatabaseFactory.open(context, dataset) }
    @After fun close() { db.close(); context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") } }
    private suspend fun setup(): Pair<SubscriptionEntity, PaymentEntity> = withContext(Dispatchers.IO) {
        val s = SubscriptionEntity(UUID.randomUUID(), "Тест", null, 999, rub, FrequencyUnit.MONTH, 1,
            RenewalMode.AUTO, LifecycleState.ACTIVE, 2027, 11, 10, 0, null, null, null, null, null, null, null, now, now)
        db.subscriptions().insert(s)
        val p = PaymentEntity(UUID.randomUUID(), s.id, UUID.randomUUID(), day.minusDays(1), 999, rub, now, PaymentSource.MANUAL_HISTORY)
        db.payments().insert(p); s to p
    }
    private fun refund(p: PaymentEntity, amount: Long) = RefundEntity(UUID.randomUUID(), p.id, day, amount, rub)
    @Test fun historyChangesNeverChangeSubscriptionOrForecast() = runBlocking {
        val (s,p) = setup(); val before = ForecastEngine().calendarMonths(listOf(s.toDomain()),day,3)
        withContext(Dispatchers.IO) {
            db.refunds().insert(refund(p,300)); db.payments().update(p.copy(amountMinor = 700)) // Existing-history fixture only.
            val stored = db.subscriptions().find(s.id)!!
            assertEquals(s,stored); assertEquals(before,ForecastEngine().calendarMonths(listOf(stored.toDomain()),day,3))
        }
    }
    @Test fun subscriptionDeletionCascadeRequiresConfirmationAndPreservesOtherFinancialFixtures() = runBlocking {
        val (s,p) = setup(); val (_,other) = setup(); val own = refund(p,300); val otherRefund = refund(other,20)
        withContext(Dispatchers.IO) { db.refunds().insert(own); db.refunds().insert(otherRefund) }
        try { LifecycleRepository(db).delete(s,false); fail() } catch (_: IllegalArgumentException) { }
        LifecycleRepository(db).delete(s,true)
        withContext(Dispatchers.IO) {
            assertNull(db.payments().find(p.id)); assertTrue(db.refunds().forPayment(p.id).isEmpty()); assertNull(db.subscriptions().find(s.id))
            assertEquals(other,db.payments().find(other.id)); assertEquals(otherRefund,db.refunds().find(otherRefund.id))
            assertTrue(db.retiredPaymentOperations().contains(p.operationId)); assertFalse(db.retiredPaymentOperations().contains(other.operationId))
        }
    }
    @Test fun monetaryHistorySurvivesReopen() = runBlocking {
        val (_,p) = setup(); val r = refund(p,300)
        withContext(Dispatchers.IO) { db.refunds().insert(r) }
        db.close(); db = TrackerDatabaseFactory.open(context,dataset)
        withContext(Dispatchers.IO) { assertEquals(p,db.payments().find(p.id)); assertEquals(r,db.refunds().find(r.id)) }
    }
    @Test fun sqlFailureRollsBackSubscriptionDeletionCascadeAndTombstone() = runBlocking {
        val (s,p) = setup(); val r = refund(p,100)
        withContext(Dispatchers.IO) {
            db.refunds().insert(r)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON refunds BEGIN SELECT RAISE(ABORT, 'injected'); END")
        }
        try { LifecycleRepository(db).delete(s,true); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        withContext(Dispatchers.IO) {
            assertEquals(s,db.subscriptions().find(s.id)); assertEquals(p,db.payments().find(p.id)); assertEquals(r,db.refunds().find(r.id))
            assertFalse(db.retiredPaymentOperations().contains(p.operationId))
        }
    }
    @Test fun subscriptionCascadeAlsoRetiresOperationIds() = runBlocking {
        val (s, p) = setup()
        com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleRepository(db).delete(s, true)
        withContext(Dispatchers.IO) {
            assertNull(db.payments().find(p.id)); assertTrue(db.retiredPaymentOperations().contains(p.operationId))
        }
    }

}
