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
    /** Read only the current calendar window; tombstones outside it remain durable dedup keys. */
    @Query("SELECT * FROM notification_delivery_ledger WHERE generation = :generation AND eventDate >= :from AND eventDate < :until ORDER BY id")
    fun inWindow(generation: UUID, from: java.time.LocalDate, until: java.time.LocalDate): List<NotificationDeliveryEntity>
    @Query("SELECT * FROM notification_delivery_ledger WHERE generation = :generation AND subscriptionId = :subscription AND state = 'PROCESSING' ORDER BY id")
    fun processing(generation: UUID, subscription: UUID): List<NotificationDeliveryEntity>
    @Query("SELECT * FROM notification_delivery_ledger WHERE claimToken = :token ORDER BY id")
    fun claimed(token: UUID): List<NotificationDeliveryEntity>
    @Query("SELECT * FROM notification_delivery_ledger WHERE generation = :generation AND eventDate < :today AND " +
        "(state NOT IN ('DELIVERED','SKIPPED') OR claimToken IS NOT NULL OR leaseUntil IS NOT NULL OR failureCount != 0 OR notBefore IS NOT NULL) " +
        "AND (leaseUntil IS NULL OR leaseUntil <= :now) ORDER BY eventDate, id LIMIT :limit")
    fun cleanupBatch(generation: UUID, today: java.time.LocalDate, now: java.time.Instant, limit: Int): List<NotificationDeliveryEntity>

}
