package com.glebskorobogatov.subscriptiontracker.data.payment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.payment.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaymentRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = LocalDate.of(2027, 11, 20)
    private val now = Instant.parse("2027-11-20T12:00:00Z")
    private fun subscription() = SubscriptionEntity(UUID.randomUUID(), "Тест", null, 49900, CurrencyCode.of("RUB"),
        FrequencyUnit.MONTH, 1, RenewalMode.MANUAL, LifecycleState.ACTIVE, 2027, 11, 15, 0,
        null, null, null, null, null, null, null, now, now)
    private fun command(s: SubscriptionEntity) = PaymentCommand(OperationId.random(), SubscriptionId(s.id), day,
        Money(59900, s.currencyCode), PaymentSource.CONFIRMED_RENEWAL, true, s.toDomain().schedule, s.renewalMode, day.plusMonths(1))
    @Test fun moneyT15DbT04ConcurrentRepeatReturnsSameReceiptAndConflictingRetryFails() = runBlocking {
        val id = UUID.randomUUID(); val db = TrackerDatabaseFactory.open(context, id)
        try {
            val s = subscription(); withContext(Dispatchers.IO) { db.subscriptions().insert(s) }
            val c = command(s); val repo = PaymentRepository(db)
            val results = coroutineScope { (1..8).map { async { repo.execute(c, day, now) } }.awaitAll() }
            assertEquals(1, results.distinct().size)
            withContext(Dispatchers.IO) {
                assertEquals(1, db.payments().all().size); assertEquals(1L, db.subscriptions().find(s.id)!!.nextCycleIndex)
            }
            try { repo.execute(c.copy(updateFuturePrice = false), day, now); fail("Conflict expected") }
            catch (_: PaymentConflict) { }
            // Identical retry is independent of a subsequent future-price/currency edit.
            withContext(Dispatchers.IO) { db.subscriptions().update(db.subscriptions().find(s.id)!!.copy(currencyCode = CurrencyCode.of("JPY"))) }
            assertEquals(results.first(), repo.execute(c, day.plusDays(1), now.plusSeconds(60)))
        } finally { db.close(); context.deleteDatabase("subscriptions-$id.db") }
    }
    @Test fun dbT05InjectedSqlFailureRollsBackPaymentAndSubscription() = runBlocking {
        val id = UUID.randomUUID(); val db = TrackerDatabaseFactory.open(context, id)
        try {
            val s = subscription()
            withContext(Dispatchers.IO) {
                db.subscriptions().insert(s)
                db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON subscriptions BEGIN SELECT RAISE(ABORT, 'injected'); END")
            }
            try { PaymentRepository(db).execute(command(s), day, now); fail("SQL failure expected") }
            catch (_: android.database.sqlite.SQLiteException) { }
            withContext(Dispatchers.IO) { assertTrue(db.payments().all().isEmpty()); assertEquals(s, db.subscriptions().find(s.id)) }
        } finally { db.close(); context.deleteDatabase("subscriptions-$id.db") }
    }
    @Test fun historyReopensAndDoesNotChangeScheduleOrCurrency() = runBlocking {
        val id = UUID.randomUUID(); var db = TrackerDatabaseFactory.open(context, id)
        try {
            val s = subscription(); withContext(Dispatchers.IO) { db.subscriptions().insert(s) }
            val c = PaymentCommand(OperationId.random(), SubscriptionId(s.id), day.minusYears(1), Money(999, CurrencyCode.of("USD")), PaymentSource.MANUAL_HISTORY)
            val payment = PaymentRepository(db).execute(c, day, now)
            withContext(Dispatchers.IO) { assertEquals(s, db.subscriptions().find(s.id)) }
            db.close(); db = TrackerDatabaseFactory.open(context, id)
            assertEquals(payment, PaymentRepository(db).execute(c, day, now))
            withContext(Dispatchers.IO) { assertEquals(listOf(payment), db.payments().all()) }
        } finally { db.close(); context.deleteDatabase("subscriptions-$id.db") }
    }
}
