package com.glebskorobogatov.subscriptiontracker.data.room

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackerDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: TrackerDatabase
    private val now = Instant.parse("2026-10-30T12:34:56.123456789Z")
    private val day = LocalDate.of(2027, 2, 28)
    private val rub = CurrencyCode.of("RUB")
    private fun id() = UUID.randomUUID()
    private fun subscription() = SubscriptionEntity(id = id(), name = "Одинаковое имя", accountLabel = "Аккаунт", priceMinor = Long.MAX_VALUE, intervalUnit = FrequencyUnit.MONTH, intervalCount = 1, state = SubscriptionState.ACTIVE, anchorYear = 2027, anchorMonth = 1, anchorDay = 31, currentPeriodStartOverride = day.minusDays(20), currentPeriodEnd = day, cancelledAccessEndExclusive = null, frozenCancelledPeriodStart = null, logoAssetId = null, catalogLogoId = null, createdAt = now, updatedAt = now, archivedAt = null)
    private fun delivery(s: SubscriptionEntity) = NotificationDeliveryEntity(id(), s.id, ReminderEventType.RENEWAL,
        day, 1, day.minusDays(1), now, DeliveryState.DELIVERED, id())
    @Test fun sharedAssetSurvivesFirstOwnerAndBecomesUnreferencedAfterLastOwner() {
        val asset = AssetEntity(id(), "assets/${id()}/images/${id()}.png", "image/png", 1, "a".repeat(64), now)
        db.assets().insert(asset)
        val first = subscription().copy(logoAssetId = asset.id)
        val second = subscription().copy(logoAssetId = asset.id)
        db.subscriptions().insert(first); db.subscriptions().insert(second)
        assertEquals(2, db.assets().references(asset.id))
        db.subscriptions().delete(first.id)
        assertEquals(1, db.assets().references(asset.id))
        assertNotNull(db.assets().find(asset.id))
        db.subscriptions().delete(second.id)
        assertEquals(0, db.assets().references(asset.id))
    }
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build() }
    @After fun close() { db.close() }

    @Test fun dbT01DuplicateNamesAllowedButDuplicateIdsRejected() {
        val a = subscription(); val b = subscription()
        db.subscriptions().insert(a); db.subscriptions().insert(b)
        assertEquals(2, db.subscriptions().all().size)
        assertThrows(SQLiteConstraintException::class.java) { db.subscriptions().insert(a) }
        assertEquals(a, db.subscriptions().find(a.id))
    }
    @Test fun dbT02AllForeignKeysRejectMissingParents() {
        val s=subscription()
        assertThrows(SQLiteConstraintException::class.java) { db.deliveries().insert(delivery(s)) }
        assertThrows(SQLiteConstraintException::class.java) { db.subscriptions().insert(s.copy(logoAssetId=id())) }
    }
    @Test fun dbT03CascadeLeavesOtherSubscriptionAndSharedAssetIntact() {
        val asset=AssetEntity(id(),"images/logo.png","image/png",42,"a".repeat(64),now)
        db.assets().insert(asset)
        val a=subscription().copy(logoAssetId=asset.id); val b=subscription().copy(logoAssetId=asset.id)
        db.subscriptions().insert(a); db.subscriptions().insert(b)
        val first=delivery(a); val second=delivery(b)
        db.deliveries().insert(first); db.deliveries().insert(second)
        assertThrows(SQLiteConstraintException::class.java) { db.assets().delete(asset.id) }
        db.subscriptions().delete(a.id)
        assertEquals(listOf(b),db.subscriptions().all()); assertEquals(listOf(second),db.deliveries().all())
        assertEquals(asset,db.assets().find(asset.id)); assertEquals(1,db.assets().references(asset.id))
    }
    @Test fun dbT04DeliveryLogicalKeyUniqueAndUpdateConflictsAbort() {
        val a=subscription(); db.subscriptions().insert(a)
        val first=delivery(a); val second=first.copy(id=id(),offsetDays=3)
        db.deliveries().insert(first); db.deliveries().insert(second)
        assertThrows(SQLiteConstraintException::class.java) { db.deliveries().insert(first.copy(id=id())) }
        assertThrows(SQLiteConstraintException::class.java) { db.deliveries().update(second.copy(offsetDays=first.offsetDays)) }
        assertEquals(second,db.deliveries().find(second.id))
    }
    @Test fun dbT05FailureRollsBackWholeTransaction() {
        val s=subscription(); db.subscriptions().insert(s)
        assertThrows(IllegalStateException::class.java) {
            db.runInTransaction {
                db.subscriptions().update(s.copy(priceMinor=10)); db.deliveries().insert(delivery(s))
                error("Injected failure")
            }
        }
        assertEquals(s,db.subscriptions().find(s.id)); assertTrue(db.deliveries().all().isEmpty())
    }
    @Test fun renewalDeliveryKeysRemainIndependentAcrossGenerations() {
        val s=subscription(); db.subscriptions().insert(s)
        val event=delivery(s); db.deliveries().insert(event)
        assertThrows(SQLiteConstraintException::class.java) { db.deliveries().insert(event.copy(id=id())) }
        db.deliveries().insert(event.copy(id=id(),generation=id()))
        assertEquals(2,db.deliveries().all().size)
    }
    @Test fun invalidValuesRejectedBeforeStorage() {
        val s=subscription()
        assertThrows(IllegalArgumentException::class.java) { s.copy(priceMinor=0) }
        assertThrows(IllegalArgumentException::class.java) { s.copy(priceMinor=-1) }
        assertThrows(IllegalArgumentException::class.java) { s.copy(intervalCount=0) }
        assertThrows(IllegalArgumentException::class.java) { s.copy(currentPeriodEnd=null) }
        assertThrows(IllegalArgumentException::class.java) { delivery(s).copy(offsetDays=2) }
    }
    @Test fun freshV5HasOnlyTargetTablesAndGeneratedSchemaColumns() {
        val sql=db.openHelper.writableDatabase
        sql.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            val tables=buildSet { while(cursor.moveToNext()) add(cursor.getString(0)) }
            assertEquals(setOf("subscriptions","assets","notification_delivery_ledger"),tables.filterNot { it.startsWith("sqlite_") || it in setOf("room_master_table","android_metadata","room_table_modification_log") }.toSet())
        }
        sql.query("PRAGMA table_info(subscriptions)").use { cursor ->
            val columns=buildSet { while(cursor.moveToNext()) add(cursor.getString(1)) }
            assertEquals(setOf("id","name","accountLabel","priceMinor","intervalUnit","intervalCount","state","anchorYear","anchorMonth","anchorDay","currentPeriodStartOverride","currentPeriodEnd","cancelledAccessEndExclusive","frozenCancelledPeriodStart","logoAssetId","catalogLogoId","createdAt","updatedAt","archivedAt"),columns)
        }
        assertEquals(5,sql.version)
    }
    @Test fun allTablesRoundTripAndReopenPreserveExactValuesAndDatasetIsolation() {
        val dataset = id(); val other = id()
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset)
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, other)
        var disk = TrackerDatabaseFactory.open(context, dataset)
        val isolated = TrackerDatabaseFactory.open(context, other)
        try {
            val asset = AssetEntity(id(), "images/logo.png", "image/png", 42, "a".repeat(64), now)
            val s = subscription().copy(logoAssetId=asset.id)
            val event=delivery(s)
            disk.assets().insert(asset); disk.subscriptions().insert(s); disk.deliveries().insert(event)
            disk.close(); disk=TrackerDatabaseFactory.open(context,dataset)
            assertEquals(s,disk.subscriptions().find(s.id)); assertEquals(asset,disk.assets().find(asset.id))
            assertEquals(event,disk.deliveries().find(event.id))
            assertTrue(isolated.subscriptions().all().isEmpty())
        } finally {
            disk.close(); isolated.close()
            context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }; context.deleteDatabase("subscriptions-$other.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$other.db") }
        }
    }
}
