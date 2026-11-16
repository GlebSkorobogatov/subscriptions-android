package com.glebskorobogatov.subscriptiontracker.data.room

import androidx.room.*
import java.util.UUID

/** Blocking DAO: invoke off the main thread; inserts and updates abort on conflicts. */
@Dao
interface SubscriptionDao {
    @Transaction
    @Query("SELECT * FROM subscriptions WHERE id = :id")
    fun observeDetails(id: UUID): kotlinx.coroutines.flow.Flow<SubscriptionWithReminders?>
    @Query("SELECT * FROM subscriptions ORDER BY id")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<SubscriptionEntity>>
    @Insert fun insert(value: SubscriptionEntity)
    @Update fun update(value: SubscriptionEntity): Int
    @Query("SELECT * FROM subscriptions ORDER BY id") fun all(): List<SubscriptionEntity>
    @Query("SELECT * FROM subscriptions WHERE id = :id") fun find(id: UUID): SubscriptionEntity?
    @Query("DELETE FROM subscriptions WHERE id = :id") fun delete(id: UUID): Int
}

/** Blocking DAO: invoke off the main thread; inserts and updates abort on conflicts. */
@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments WHERE subscriptionId = :id ORDER BY paidOn, id")
    fun forSubscription(id: UUID): List<PaymentEntity>
    @Query("SELECT * FROM payments WHERE operationId = :id")
    fun findByOperation(id: UUID): PaymentEntity?
    @Insert fun insert(value: PaymentEntity)
    @Update fun update(value: PaymentEntity): Int
    @Query("SELECT * FROM payments ORDER BY id") fun all(): List<PaymentEntity>
    @Query("SELECT * FROM payments WHERE id = :id") fun find(id: UUID): PaymentEntity?
    @Query("DELETE FROM payments WHERE id = :id") fun delete(id: UUID): Int
}

/** Blocking DAO: invoke off the main thread; inserts and updates abort on conflicts. */
@Dao
interface RefundDao {
    @Query("SELECT * FROM refunds WHERE paymentId = :id ORDER BY refundedOn, id")
    fun forPayment(id: UUID): List<RefundEntity>
    @Insert fun insert(value: RefundEntity)
    @Update fun update(value: RefundEntity): Int
    @Query("SELECT * FROM refunds ORDER BY id") fun all(): List<RefundEntity>
    @Query("SELECT * FROM refunds WHERE id = :id") fun find(id: UUID): RefundEntity?
    @Query("DELETE FROM refunds WHERE id = :id") fun delete(id: UUID): Int
}

/** Blocking DAO: invoke off the main thread; inserts and updates abort on conflicts. */
@Dao
interface AssetDao {
    @Insert fun insert(value: AssetEntity)
    @Update fun update(value: AssetEntity): Int
    @Query("SELECT * FROM assets ORDER BY id") fun all(): List<AssetEntity>
    @Query("SELECT * FROM assets WHERE id = :id") fun find(id: UUID): AssetEntity?
    @Query("SELECT COUNT(*) FROM subscriptions WHERE logoAssetId = :id") fun references(id: UUID): Int
    @Query("DELETE FROM assets WHERE id = :id") fun delete(id: UUID): Int
}

/** Blocking DAO: invoke off the main thread; inserts and updates abort on conflicts. */
@Dao
interface NotificationDeliveryDao {
    @Insert fun insert(value: NotificationDeliveryEntity)
    @Update fun update(value: NotificationDeliveryEntity): Int
    @Query("SELECT * FROM notification_delivery_ledger ORDER BY id") fun all(): List<NotificationDeliveryEntity>
    @Query("SELECT * FROM notification_delivery_ledger WHERE id = :id") fun find(id: UUID): NotificationDeliveryEntity?
    @Query("DELETE FROM notification_delivery_ledger WHERE id = :id") fun delete(id: UUID): Int
}

@Dao
interface ReminderOverrideDao {
    @Query("SELECT * FROM reminder_overrides ORDER BY subscriptionId, eventType")
    fun all(): List<ReminderOverrideEntity>
    @Insert fun insert(value: ReminderOverrideEntity)
    @Update fun update(value: ReminderOverrideEntity): Int
    @Query("SELECT * FROM reminder_overrides WHERE subscriptionId = :id ORDER BY eventType")
    fun forSubscription(id: UUID): List<ReminderOverrideEntity>
    @Query("DELETE FROM reminder_overrides WHERE subscriptionId = :id AND eventType = :type")
    fun delete(id: UUID, type: ReminderEventType): Int
}
