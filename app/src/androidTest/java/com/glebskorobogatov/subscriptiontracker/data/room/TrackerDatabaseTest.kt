package com.glebskorobogatov.subscriptiontracker.data.room

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    private fun subscription() = SubscriptionEntity(id(), "Одинаковое имя", "Аккаунт", Long.MAX_VALUE,
        rub, FrequencyUnit.MONTH, 1, RenewalMode.AUTO, LifecycleState.ACTIVE, 2027, 1, 31, 1,
        day.plusDays(1), 1, day.minusDays(20), null, 7, null, "catalog-logo", now, now)
    private fun payment(s: SubscriptionEntity) = PaymentEntity(id(), s.id, id(), day, Long.MAX_VALUE, rub, now, PaymentSource.MANUAL_HISTORY)
    private fun refund(p: PaymentEntity) = RefundEntity(id(), p.id, day, 1, rub)
    private fun reminder(s: SubscriptionEntity) = ReminderOverrideEntity(s.id, ReminderEventType.PAYMENT, ReminderMode.CUSTOM, emptySet())
    private fun delivery(s: SubscriptionEntity) = NotificationDeliveryEntity(id(), s.id, ReminderEventType.PAYMENT,
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
        val s = subscription(); val p = payment(s)
        assertThrows(SQLiteConstraintException::class.java) { db.payments().insert(p) }
        assertThrows(SQLiteConstraintException::class.java) { db.refunds().insert(refund(p)) }
        assertThrows(SQLiteConstraintException::class.java) { db.reminders().insert(reminder(s)) }
        assertThrows(SQLiteConstraintException::class.java) { db.deliveries().insert(delivery(s)) }
        assertThrows(SQLiteConstraintException::class.java) { db.subscriptions().insert(s.copy(logoAssetId = id())) }
    }
    @Test fun dbT03CascadeLeavesOtherSubscriptionAndSharedAssetIntact() {
        val asset = AssetEntity(id(), "images/logo.png", "image/png", 42, "a".repeat(64), now)
        db.assets().insert(asset)
        val a = subscription().copy(logoAssetId = asset.id); val b = subscription().copy(logoAssetId = asset.id)
        db.subscriptions().insert(a); db.subscriptions().insert(b)
        val pa = payment(a); val pb = payment(b)
        db.payments().insert(pa); db.payments().insert(pb)
        db.refunds().insert(refund(pa)); val rb = refund(pb); db.refunds().insert(rb)
        db.reminders().insert(reminder(a)); db.reminders().insert(reminder(b))
        val da = delivery(a); val dbb = delivery(b); db.deliveries().insert(da); db.deliveries().insert(dbb)
        assertThrows(SQLiteConstraintException::class.java) { db.assets().delete(asset.id) }
        db.subscriptions().delete(a.id)
        assertEquals(listOf(b), db.subscriptions().all()); assertEquals(listOf(pb), db.payments().all())
        assertEquals(listOf(rb), db.refunds().all()); assertTrue(db.reminders().forSubscription(a.id).isEmpty())
        assertEquals(listOf(reminder(b)), db.reminders().forSubscription(b.id))
        assertEquals(listOf(dbb), db.deliveries().all()); assertEquals(asset, db.assets().find(asset.id))
        db.payments().delete(pb.id); assertTrue(db.refunds().all().isEmpty())
    }
    @Test fun dbT04OperationIdUniqueAcrossSubscriptionsAndUpdateConflictsAbort() {
        val a = subscription(); val b = subscription(); db.subscriptions().insert(a); db.subscriptions().insert(b)
        val first = payment(a); val second = payment(b)
        db.payments().insert(first); db.payments().insert(second)
        assertThrows(SQLiteConstraintException::class.java) { db.payments().insert(payment(b).copy(operationId = first.operationId)) }
        assertThrows(SQLiteConstraintException::class.java) { db.payments().update(second.copy(operationId = first.operationId)) }
        assertEquals(second, db.payments().find(second.id))
    }
    @Test fun dbT05FailureRollsBackWholeTransaction() {
        val s = subscription(); db.subscriptions().insert(s); val p = payment(s); db.payments().insert(p)
        assertThrows(IllegalStateException::class.java) {
            db.runInTransaction {
                db.payments().update(p.copy(amountMinor = 10))
                db.refunds().insert(refund(p))
                error("Injected failure")
            }
        }
        assertEquals(p, db.payments().find(p.id)); assertTrue(db.refunds().all().isEmpty())
    }
    @Test fun reminderAndDeliveryKeysAreUnique() {
        val s = subscription(); db.subscriptions().insert(s)
        val r = reminder(s); db.reminders().insert(r)
        assertThrows(SQLiteConstraintException::class.java) { db.reminders().insert(r) }
        val custom = r.copy(offsets = setOf(7, 3, 1, 0)); db.reminders().update(custom)
        assertEquals(listOf(custom), db.reminders().forSubscription(s.id))
        db.reminders().insert(r.copy(eventType = ReminderEventType.CANCELLATION))
        val event = delivery(s); db.deliveries().insert(event)
        assertThrows(SQLiteConstraintException::class.java) { db.deliveries().insert(event.copy(id = id())) }
        db.deliveries().insert(event.copy(id = id(), generation = id()))
    }
    @Test fun invalidValuesRejectedBeforeStorage() {
        val s = subscription(); val p = payment(s)
        assertThrows(IllegalArgumentException::class.java) { s.copy(priceMinor = 0) }
        assertThrows(IllegalArgumentException::class.java) { p.copy(amountMinor = -1) }
        assertThrows(IllegalArgumentException::class.java) { refund(p).copy(amountMinor = 0) }
        assertThrows(IllegalArgumentException::class.java) { s.copy(intervalCount = 0) }
        assertThrows(IllegalArgumentException::class.java) { s.copy(overrideCycleIndex = null) }
        assertThrows(IllegalArgumentException::class.java) { reminder(s).copy(offsets = setOf(2)) }
    }
    @Test fun allTablesRoundTripAndReopenPreserveExactValuesAndDatasetIsolation() {
        val dataset = id(); val other = id()
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset)
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, other)
        var disk = TrackerDatabaseFactory.open(context, dataset)
        val isolated = TrackerDatabaseFactory.open(context, other)
        try {
            val asset = AssetEntity(id(), "images/logo.png", "image/png", 42, "a".repeat(64), now)
            val s = subscription().copy(logoAssetId = asset.id, paidAccessEndDate = day.plusYears(1))
            val p = payment(s); val r = refund(p); val rule = reminder(s); val event = delivery(s)
            disk.assets().insert(asset); disk.subscriptions().insert(s); disk.payments().insert(p)
            disk.refunds().insert(r); disk.reminders().insert(rule); disk.deliveries().insert(event)
            disk.close(); disk = TrackerDatabaseFactory.open(context, dataset)
            assertEquals(s, disk.subscriptions().find(s.id)); assertEquals(p, disk.payments().find(p.id))
            assertEquals(r, disk.refunds().find(r.id)); assertEquals(asset, disk.assets().find(asset.id))
            assertEquals(event, disk.deliveries().find(event.id)); assertEquals(listOf(rule), disk.reminders().forSubscription(s.id))
            assertTrue(isolated.subscriptions().all().isEmpty())
        } finally {
            disk.close(); isolated.close()
            context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }; context.deleteDatabase("subscriptions-$other.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$other.db") }
        }
    }
}
