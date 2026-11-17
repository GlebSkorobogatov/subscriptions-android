package com.glebskorobogatov.subscriptiontracker.data.room

import androidx.room.*
import java.util.UUID

/** Blocking DAO: invoke off the main thread; inserts and updates abort on conflicts. */
@Dao
interface SubscriptionDao {
    @Transaction
    @Query("SELECT * FROM subscriptions WHERE id = :id")
    fun observeDetails(id: UUID): kotlinx.coroutines.flow.Flow<SubscriptionEntity?>
    @Query("SELECT * FROM subscriptions ORDER BY id")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<SubscriptionEntity>>
    @Insert fun insert(value: SubscriptionEntity)
    @Update fun update(value: SubscriptionEntity): Int
    @Query("SELECT * FROM subscriptions ORDER BY id") fun all(): List<SubscriptionEntity>
    @Query("SELECT * FROM subscriptions WHERE id = :id") fun find(id: UUID): SubscriptionEntity?
    @Query("DELETE FROM subscriptions WHERE id = :id") fun delete(id: UUID): Int
}

/** Blocking DAO: invoke off the main thread. */
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
