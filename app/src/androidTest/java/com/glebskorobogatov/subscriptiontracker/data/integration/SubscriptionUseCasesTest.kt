package com.glebskorobogatov.subscriptiontracker.data.integration

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.fixtures.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionUseCasesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: TrackerDatabase
    private lateinit var lease: DatasetSettingsFactory.Lease
    private lateinit var use: SubscriptionUseCases
    private lateinit var dataset: UUID
    private val day = LocalDate.of(2027, 1, 31)
    private val now = Instant.parse("2027-01-31T12:00:00Z")
    private val rub = CurrencyCode.of("RUB")
    private val queries = CopyOnWriteArrayList<String>()
    @Before fun open() = runBlocking {
        dataset = UUID.randomUUID(); com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset); db = TrackerDatabaseFactory.open(context, dataset) { fresh ->
            Room.databaseBuilder(context,TrackerDatabase::class.java,"subscriptions-$dataset.db")
                .openHelperFactory(PreservingOpenHelperFactory(fresh))
                .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> queries += sql },Executor { it.run() }).build()
        }
        lease = DatasetSettingsFactory.acquire(context, dataset)
        use = SubscriptionUseCases(db, lease.repository, CurrentDate(day))
    }
    @After fun close() = runBlocking { db.close(); lease.close(); context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }; Unit }
    private suspend fun create(first: LocalDate = day) = use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Сервис","Аккаунт",RubPrice(999),ScheduleEdit(first,Frequency(FrequencyUnit.MONTH,1))),now)
    private fun edit(s: SubscriptionEntity,schedule: ScheduleEdit? = null,price: Long = s.priceMinor) = SubscriptionEdit(s.name,s.accountLabel,RubPrice(price),schedule)
    private suspend fun details(id: UUID) = withTimeout(5000) { use.details(id).first() }!!
    @Test fun realRoomFlowsUpdateDetailsWithoutReadingFinancialTables() = runBlocking {
        val s=create(); val events=Channel<SubscriptionDetails?>(Channel.UNLIMITED)
        val reader=launch { use.details(s.id).collect { events.send(it) } }
        try {
            assertEquals(s,withTimeout(5000) { events.receive() }!!.subscription)
            val edited=use.editSubscription(s,edit(s,price=12345),now.plusSeconds(1))
            withTimeout(5000) { events.receiveAsFlow().first { it?.subscription==edited } }
            use.deleteSubscription(edited,true)
            withTimeout(5000) { events.receiveAsFlow().first { it==null } }
            assertTrue(queries.none { sql -> listOf("payments","refunds","reminder_overrides").any { sql.contains(it,true) } })
        } finally { reader.cancelAndJoin(); events.close() }
    }
    @Test fun duplicateNamesHaveIndependentIdsAndKopecks() = runBlocking {
        val a=create(); val b=create()
        assertNotEquals(a.id,b.id); assertEquals(a.name,b.name); assertEquals(999L,details(a.id).subscription.priceMinor)
    }
    @Test fun priceAndNameEditsPreserveExactCalendarAnchor() = runBlocking {
        val s=create(); use.date.update(day.plusDays(1))
        val edited=use.editSubscription(s,edit(s,price=12345).copy(name="Новое имя"),now.plusSeconds(1))
        assertEquals(s.anchorYear,edited.anchorYear); assertEquals(s.anchorMonth,edited.anchorMonth); assertEquals(31,edited.anchorDay)
        assertEquals(LocalDate.of(2027,2,28),details(s.id).period.due)
        use.date.update(LocalDate.of(2027,3,1)); assertEquals(LocalDate.of(2027,3,31),details(s.id).period.due)
    }
    @Test fun explicitDateReplacesWholeSeriesAndResetsPeriodOverride() = runBlocking {
        val s=create()
        val edited=use.editSubscription(s,edit(s,ScheduleEdit(day.plusDays(1),Frequency(FrequencyUnit.MONTH,1))),now.plusSeconds(1))
        assertEquals(1,edited.anchorDay); assertEquals(2,edited.anchorMonth); assertNull(edited.currentPeriodEnd)
        use.date.update(LocalDate.of(2027,3,1)); assertEquals(LocalDate.of(2027,3,1),details(s.id).period.due)
    }
    @Test fun explicit31InFebruaryRestoresMarchAndScopedStartExpires() = runBlocking {
        val feb=LocalDate.of(2027,2,28); use.date.update(feb.minusDays(5))
        val s=use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Сервис",null,RubPrice(1),ScheduleEdit(feb,Frequency(FrequencyUnit.MONTH,1),31),PeriodStart(feb.minusDays(10),feb)),now)
        assertEquals(0.5,details(s.id).period.remainingFraction!!,0.0001)
        use.date.update(feb.plusDays(1)); assertEquals(LocalDate.of(2027,3,31),details(s.id).period.due)
        assertEquals(31,s.anchorDay)
    }
    @Test fun cancelledExclusiveEndCanChangeWithoutChangingFrozenStartAndNeverAutoArchives() = runBlocking {
        val s=create(); val end=day.plusDays(3)
        val cancelled=use.changeLifecycle(s,LifecycleCommand.Cancel(end),now)
        val start=cancelled.frozenCancelledPeriodStart
        val edited=use.changeLifecycle(cancelled,LifecycleCommand.EditEnd(end.plusDays(4)),now.plusSeconds(1))
        assertEquals(start,edited.frozenCancelledPeriodStart)
        assertTrue(use.forecasts.first().forecast.next30Days.charges.isEmpty())
        use.refreshToday(end.plusYears(5),now.plusSeconds(2))
        assertEquals(SubscriptionState.CANCELLED,details(s.id).subscription.state); assertNull(details(s.id).period.remainingFraction)
    }
    @Test fun manualArchiveAndRestoreKeepIdentityAndAppearance() = runBlocking {
        val s=create(); val cancelled=use.changeLifecycle(s,LifecycleCommand.Cancel(),now)
        val archived=use.changeLifecycle(cancelled,LifecycleCommand.Archive,now.plusSeconds(1))
        assertEquals(day,archived.archivedAt)
        val restored=use.changeLifecycle(archived,LifecycleCommand.Reactivate(RubPrice(123),ScheduleEdit(day.plusDays(1),Frequency(FrequencyUnit.WEEK,2))),now.plusSeconds(2))
        assertEquals(s.id,restored.id); assertEquals(s.logoAssetId,restored.logoAssetId)
        assertNull(restored.cancelledAccessEndExclusive); assertNull(restored.frozenCancelledPeriodStart); assertNull(restored.archivedAt)
        assertEquals(SubscriptionState.ACTIVE,restored.state)
    }
    @Test fun staleSnapshotsCannotDeleteOrArchiveAfterReactivation() = runBlocking {
        val s=create(); val archived=use.changeLifecycle(s,LifecycleCommand.Archive,now)
        val restored=use.changeLifecycle(archived,LifecycleCommand.Reactivate(RubPrice(100),ScheduleEdit(day.plusDays(1),Frequency(FrequencyUnit.MONTH,1))),now.plusSeconds(1))
        try { use.deleteSubscription(s,true); fail() } catch (_: com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict) { }
        try { use.changeLifecycle(archived,LifecycleCommand.Archive,now); fail() } catch (_: com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict) { }
        assertEquals(restored,details(s.id).subscription)
    }
    @Test fun confirmedDeletionCascadesOnlySelectedLedger() = runBlocking {
        val a=create(); val b=create()
        withContext(Dispatchers.IO) {
            for(s in listOf(a,b)) db.deliveries().insert(NotificationDeliveryEntity(UUID.randomUUID(),s.id,ReminderEventType.RENEWAL,day,0,day,now,DeliveryState.DELIVERED,dataset))
        }
        try { use.deleteSubscription(a,false); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(a,details(a.id).subscription); use.deleteSubscription(a,true)
        assertNull(use.details(a.id).first()); assertEquals(b,details(b.id).subscription)
        withContext(Dispatchers.IO) { assertEquals(listOf(b.id),db.deliveries().all().map { it.subscriptionId }) }
    }
    @Test fun concurrentDateEditOrArchiveUsesFullExpectedSnapshot() = runBlocking {
        val s=create(); val results=coroutineScope { listOf<suspend ()->Unit>(
            { use.editSubscription(s,edit(s,ScheduleEdit(day.plusDays(1),Frequency(FrequencyUnit.MONTH,1))),now.plusSeconds(1)); Unit },
            { use.changeLifecycle(s,LifecycleCommand.Archive,now.plusSeconds(1)); Unit }
        ).map { block -> async { try { block(); true } catch (_: com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict) { false } } }.awaitAll() }
        assertEquals(1,results.count { it })
    }
    @Test fun injectedSqlFailureRollsBackAllSubscriptionFields() = runBlocking {
        val s=create()
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON subscriptions BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        try { use.editSubscription(s,edit(s,price=100),now); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(s,details(s.id).subscription)
    }
    @Test fun settingsAndSubscriptionsReopenUsingReadyWithoutFreshAuthorization() = runBlocking {
        val s=create(); use.updateSettings { it.copy(renewalOffsets=emptySet(),theme=Theme.DARK) }
        db.close(); lease.close(); db=TrackerDatabaseFactory.open(context,dataset); lease=DatasetSettingsFactory.acquire(context,dataset)
        use=SubscriptionUseCases(db,lease.repository,CurrentDate(day))
        assertEquals(s,details(s.id).subscription); assertEquals(Theme.DARK,use.settings.first().theme); assertTrue(use.settings.first().renewalOffsets.isEmpty())
    }
    @Test fun startupAndResumeDateRefreshKeepMutationGuardAndReconciliationWithoutRoomWrites() = runBlocking {
        val s=create(); var guarded=0; var reconciled=0
        val changes=com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderChanges(
            validate={ guarded++ },request={ reconciled++ })
        val guardedUse=SubscriptionUseCases(db,lease.repository,CurrentDate(day),reminderChanges=changes)
        guardedUse.refreshToday(day.plusYears(5),now)
        assertEquals(1,guarded); assertEquals(1,reconciled); assertEquals(s,details(s.id).subscription)
    }
    @Test fun priceEditKeepsScopedPeriodStartAndExplicitClearDoesNotShiftAnchor() = runBlocking {
        val s=use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Сервис",null,RubPrice(100),ScheduleEdit(day,Frequency(FrequencyUnit.MONTH,1)),PeriodStart(day.minusDays(10),day)),now)
        val edited=use.editSubscription(s,edit(s,price=101),now.plusSeconds(1))
        assertEquals(s.currentPeriodStartOverride,edited.currentPeriodStartOverride); assertEquals(s.currentPeriodEnd,edited.currentPeriodEnd)
        val cleared=use.editSubscription(edited,edit(edited).copy(periodStart=PeriodStartEdit.Set(null)),now.plusSeconds(2))
        assertNull(cleared.currentPeriodStartOverride); assertNull(cleared.currentPeriodEnd); assertEquals(31,cleared.anchorDay)
    }
    @Test fun fullSnapshotRejectsStalePriceEvenAfterReturningToSameScheduleAndState() = runBlocking {
        val s=create(); val archived=use.changeLifecycle(s,LifecycleCommand.Archive,now.plusSeconds(1))
        val restored=use.changeLifecycle(archived,LifecycleCommand.Reactivate(RubPrice(s.priceMinor+1),ScheduleEdit(day,Frequency(FrequencyUnit.MONTH,1))),now.plusSeconds(2))
        assertEquals(s.anchorDay,restored.anchorDay); assertEquals(s.state,restored.state)
        try { use.editSubscription(s,edit(s),now); fail() } catch (_: com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict) { }
        assertEquals(restored,details(s.id).subscription)
    }
}
