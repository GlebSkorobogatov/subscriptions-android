package com.glebskorobogatov.subscriptiontracker.data.payment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.fixtures.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyPaymentStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = LocalDate.of(2027, 11, 20)
    private val now = Instant.parse("2027-11-20T12:00:00Z")
    private fun subscription() = SubscriptionEntity(UUID.randomUUID(), "Тест", null, 49900, CurrencyCode.of("RUB"),
        FrequencyUnit.MONTH, 1, RenewalMode.MANUAL, LifecycleState.ACTIVE, 2027, 11, 15, 0,
        null, null, null, null, null, null, null, now, now)
    @Test fun concurrentLifecycleCommandsCommitOneExpectedSnapshotAndPreserveLegacyPayment() = runBlocking {
        val id = UUID.randomUUID(); authorizeFreshFixture(context,id); val db = TrackerDatabaseFactory.open(context,id)
        try {
            val s = subscription(); withContext(Dispatchers.IO) { db.subscriptions().insert(s) }
            val p = legacyPayment(db,s,day)
            val results = coroutineScope { (1..8).map { async {
                try { LifecycleRepository(db).execute(s,LifecycleCommand.Archive,now.plusSeconds(1)); true }
                catch (_: LifecycleConflict) { false }
            } }.awaitAll() }
            assertEquals(1,results.count { it })
            withContext(Dispatchers.IO) { assertEquals(listOf(p),db.payments().all()); assertEquals(0L,db.subscriptions().find(s.id)!!.nextCycleIndex) }
        } finally { db.close(); context.deleteDatabase("subscriptions-$id.db"); forgetFixtureInitialization(context,"subscriptions-$id.db") }
    }
    @Test fun injectedLifecycleFailureRollsBackSubscriptionAndPreservesExistingPayment() = runBlocking {
        val id = UUID.randomUUID(); authorizeFreshFixture(context,id); val db = TrackerDatabaseFactory.open(context,id)
        try {
            val s = subscription(); withContext(Dispatchers.IO) { db.subscriptions().insert(s) }
            val p = legacyPayment(db,s,day)
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON subscriptions BEGIN SELECT RAISE(ABORT, 'injected'); END") }
            try { LifecycleRepository(db).execute(s,LifecycleCommand.Archive,now); fail() } catch (_: android.database.sqlite.SQLiteException) { }
            withContext(Dispatchers.IO) { assertEquals(s,db.subscriptions().find(s.id)); assertEquals(listOf(p),db.payments().all()) }
        } finally { db.close(); context.deleteDatabase("subscriptions-$id.db"); forgetFixtureInitialization(context,"subscriptions-$id.db") }
    }
    @Test fun historyReopensAndDoesNotChangeScheduleOrCurrency() = runBlocking {
        val id = UUID.randomUUID(); authorizeFreshFixture(context,id); var db = TrackerDatabaseFactory.open(context,id)
        try {
            val s = subscription(); withContext(Dispatchers.IO) { db.subscriptions().insert(s) }
            val p = legacyPayment(db,s,day,999,CurrencyCode.of("USD"))
            db.close(); db = TrackerDatabaseFactory.open(context,id)
            withContext(Dispatchers.IO) { assertEquals(s,db.subscriptions().find(s.id)); assertEquals(listOf(p),db.payments().all()) }
        } finally { db.close(); context.deleteDatabase("subscriptions-$id.db"); forgetFixtureInitialization(context,"subscriptions-$id.db") }
    }

}
