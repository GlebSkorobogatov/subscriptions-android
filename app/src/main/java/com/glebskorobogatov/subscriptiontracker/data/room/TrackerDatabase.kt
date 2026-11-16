package com.glebskorobogatov.subscriptiontracker.data.room

import android.content.Context
import androidx.room.*
import java.util.UUID

@Database(entities = [SubscriptionEntity::class, PaymentEntity::class, RefundEntity::class,
    ReminderOverrideEntity::class, NotificationDeliveryEntity::class, AssetEntity::class, RetiredPaymentOperation::class, RetiredRefundId::class],
    version = 4, exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)])
@TypeConverters(StorageConverters::class)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun retiredPaymentOperations(): RetiredPaymentOperationDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun payments(): PaymentDao
    abstract fun refunds(): RefundDao
    abstract fun reminders(): ReminderOverrideDao
    abstract fun deliveries(): NotificationDeliveryDao
    abstract fun assets(): AssetDao
}

/** Explicit dataset selection; activation, file cleanup and atomic restore belong to stage H. */
object TrackerDatabaseFactory {
    fun open(context: Context, datasetId: UUID): TrackerDatabase =
        Room.databaseBuilder(context.applicationContext, TrackerDatabase::class.java,
            "subscriptions-$datasetId.db").build()
}
