package com.glebskorobogatov.subscriptiontracker.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.LifecycleCommand
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine
import com.glebskorobogatov.subscriptiontracker.fixtures.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Transaction/reopen regressions moved from old payment/refund fixtures to actual v5 data. */
class SubscriptionTransactionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = LocalDate.of(2027,11,20)
    private val now = Instant.parse("2027-11-20T12:00:00.123456789Z")
    private lateinit var dataset: UUID
    private lateinit var db: TrackerDatabase
    @Before fun open() {
        dataset=UUID.randomUUID(); authorizeFreshFixture(context,dataset)
        db=TrackerDatabaseFactory.open(context,dataset)
    }
    @After fun close() {
        db.close(); context.deleteDatabase("subscriptions-$dataset.db")
        forgetFixtureInitialization(context,"subscriptions-$dataset.db")
    }
    private suspend fun fixture(): Pair<SubscriptionEntity,NotificationDeliveryEntity> = withContext(Dispatchers.IO) {
        val row=SubscriptionDomain().create(SubscriptionId.random(),"Тест",RubPrice(49900),
            ScheduleEdit(day,Frequency(FrequencyUnit.MONTH,1)),day).toEntity(now)
        val event=NotificationDeliveryEntity(UUID.randomUUID(),row.id,ReminderEventType.RENEWAL,
            day,1,day.minusDays(1),null,DeliveryState.PENDING,dataset)
        db.subscriptions().insert(row); db.deliveries().insert(event)
        row to event
    }
    @Test fun concurrentLifecycleCommandsCommitOneSnapshotAndPreserveLedger() = runBlocking {
        val (row,event)=fixture()
        val results=coroutineScope { List(8) { async {
            try { LifecycleRepository(db).execute(row,LifecycleCommand.Archive,now.plusSeconds(1),day); true }
            catch (_: LifecycleConflict) { false }
        } }.awaitAll() }
        assertEquals(1,results.count { it })
        withContext(Dispatchers.IO) { assertEquals(event,db.deliveries().find(event.id)); assertEquals(SubscriptionState.ARCHIVED,db.subscriptions().find(row.id)!!.state) }
    }
    @Test fun injectedLifecycleFailureRollsBackSubscriptionAndPreservesLedger() = runBlocking {
        val (row,event)=fixture()
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON subscriptions BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        try { LifecycleRepository(db).execute(row,LifecycleCommand.Archive,now,day); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        withContext(Dispatchers.IO) { assertEquals(row,db.subscriptions().find(row.id)); assertEquals(event,db.deliveries().find(event.id)) }
    }
    @Test fun reopenPreservesCompleteSubscriptionAndDeliveryMetadataUsingReady() = runBlocking {
        val (row,event)=fixture()
        db.close(); db=TrackerDatabaseFactory.open(context,dataset)
        withContext(Dispatchers.IO) { assertEquals(row,db.subscriptions().find(row.id)); assertEquals(event,db.deliveries().find(event.id)) }
    }
    @Test fun failedLedgerCascadeRollsBackParentAndDependentRows() = runBlocking {
        val (row,event)=fixture()
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON notification_delivery_ledger BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        try { LifecycleRepository(db).delete(row,true); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        withContext(Dispatchers.IO) { assertEquals(row,db.subscriptions().find(row.id)); assertEquals(event,db.deliveries().find(event.id)) }
    }
    @Test fun deliveryAcknowledgementCannotChangeSubscriptionOrForecast() = runBlocking {
        val (row,event)=fixture()
        val before=ForecastEngine().calendarMonths(listOf(row.toDomain()),day,3)
        withContext(Dispatchers.IO) {
            db.deliveries().update(event.copy(state=DeliveryState.DELIVERED,deliveredAt=now))
            val stored=db.subscriptions().find(row.id)!!
            assertEquals(row,stored); assertEquals(before,ForecastEngine().calendarMonths(listOf(stored.toDomain()),day,3))
        }
    }
    @Test fun confirmationAndCascadeAffectOnlySelectedSubscription() = runBlocking {
        val (first,event)=fixture(); val (second,other)=fixture()
        try { LifecycleRepository(db).delete(first,false); fail() } catch (_: IllegalArgumentException) { }
        withContext(Dispatchers.IO) { assertEquals(first,db.subscriptions().find(first.id)); assertEquals(event,db.deliveries().find(event.id)) }
        LifecycleRepository(db).delete(first,true)
        withContext(Dispatchers.IO) {
            assertNull(db.subscriptions().find(first.id)); assertNull(db.deliveries().find(event.id))
            assertEquals(second,db.subscriptions().find(second.id)); assertEquals(other,db.deliveries().find(other.id))
        }
    }
}
