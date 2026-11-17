package com.glebskorobogatov.subscriptiontracker.data.lifecycle

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
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
    private fun row() = SubscriptionEntity(id = UUID.randomUUID(), name = "Одинаковый сервис", accountLabel = null, priceMinor = 100, intervalUnit = FrequencyUnit.MONTH, intervalCount = 1, state = SubscriptionState.ACTIVE, anchorYear = 2027, anchorMonth = 11, anchorDay = 10, currentPeriodStartOverride = null, currentPeriodEnd = null, cancelledAccessEndExclusive = null, frozenCancelledPeriodStart = null, logoAssetId = null, catalogLogoId = null, createdAt = now, updatedAt = now, archivedAt = null)
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrackerDatabase::class.java).build(); repo = LifecycleRepository(db) }
    @After fun close() { db.close() }
    private suspend fun insert(s: SubscriptionEntity) = withContext(Dispatchers.IO) { db.subscriptions().insert(s) }
    private suspend fun read(s: SubscriptionEntity) = withContext(Dispatchers.IO) { db.subscriptions().find(s.id)!! }
    @Test fun concurrentCommandsCommitOneExpectedSnapshot() = runBlocking {
        val row=row(); insert(row)
        val results=coroutineScope { List(4) { async { try { repo.execute(row,LifecycleCommand.Cancel(),now,day); true } catch (_: LifecycleConflict) { false } } }.awaitAll() }
        assertEquals(1,results.count { it }); assertEquals(SubscriptionState.CANCELLED,read(row).state)
        assertEquals(day,read(row).cancelledAccessEndExclusive)
    }
    @Test fun cancellationFreezesStartAndEditingEndNeverMovesIt() = runBlocking {
        val s=row(); insert(s)
        val cancelled=repo.execute(s,LifecycleCommand.Cancel(),now,day)
        val start=cancelled.frozenCancelledPeriodStart
        assertEquals(day.minusMonths(1),start)
        val edited=repo.execute(cancelled,LifecycleCommand.EditEnd(day.plusDays(4)),now.plusSeconds(1),day)
        assertEquals(start,edited.frozenCancelledPeriodStart)
        assertEquals(s.anchorDay,edited.anchorDay)
        assertEquals(SubscriptionState.CANCELLED,read(edited).state)
    }
    @Test fun archiveRestorePreservesIdAppearanceAndValidatesNewContract() = runBlocking {
        val a=row(); val b=row(); insert(a); insert(b)
        val cancelled=repo.execute(a,LifecycleCommand.Cancel(day.plusDays(5)),now,day)
        val archived=repo.execute(cancelled,LifecycleCommand.Archive,now,day)
        assertEquals(day,archived.archivedAt)
        val active=repo.execute(archived,LifecycleCommand.Reactivate(RubPrice(900),ScheduleEdit(day.plusDays(3),Frequency(FrequencyUnit.YEAR,1))),now.plusSeconds(1),day)
        assertEquals(a.id,active.id); assertEquals(a.catalogLogoId,active.catalogLogoId); assertNull(active.archivedAt)
        assertNull(active.cancelledAccessEndExclusive); assertNull(active.frozenCancelledPeriodStart); assertEquals(b,read(b))
    }
    @Test fun lifeT14T15CascadeDeletionRequiresConfirmationAndRetainsOtherRecords() = runBlocking {
        val a=row(); val b=row(); insert(a); insert(b)
        withContext(Dispatchers.IO) { db.deliveries().insert(NotificationDeliveryEntity(UUID.randomUUID(),a.id,ReminderEventType.RENEWAL,day,0,day,now,DeliveryState.DELIVERED,UUID.randomUUID())) }
        try { repo.delete(a,false); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(a,read(a)); repo.delete(a,true)
        withContext(Dispatchers.IO) {
            assertNull(db.subscriptions().find(a.id)); assertEquals(b,db.subscriptions().find(b.id)); assertTrue(db.deliveries().all().isEmpty())
        }
    }
    @Test fun lifeT17ConcurrentCommandsAndStaleDeletionCannotOverwrite() = runBlocking {
        val s=row(); insert(s)
        val outcomes=coroutineScope { listOf(LifecycleCommand.Cancel(day),LifecycleCommand.Archive).map { command ->
            async { try { repo.execute(s,command,now,day); true } catch (_: LifecycleConflict) { false } }
        }.awaitAll() }
        assertEquals(1,outcomes.count { it }); try { repo.delete(s,true); fail() } catch (_: LifecycleConflict) { }
    }
    @Test fun injectedUpdateFailureRollsBackFullSnapshot() = runBlocking {
        val s=row(); insert(s)
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON subscriptions BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        try { repo.execute(s,LifecycleCommand.Cancel(),now,day); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(s,read(s))
    }
}
