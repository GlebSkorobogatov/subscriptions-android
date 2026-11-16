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

    /** Use Room's generated validator even when the identity hash still matches a modified schema.
     * This reads metadata only and never invokes create/drop/migration callbacks.
     */
    @android.annotation.SuppressLint("RestrictedApi") // Room 2.8 generated delegate is pinned; reuse its exact validator, not a duplicate schema.
    internal fun validateOpenedSchema() {
        val delegate = createOpenDelegate() as RoomOpenDelegate
        val result = delegate.onValidateSchema(androidx.sqlite.driver.SupportSQLiteConnection(openHelper.writableDatabase))
        if (!result.isValid) throw StorageUnavailable(StorageFailure.INCOMPATIBLE)
    }
}

/** Explicit dataset selection; activation, file cleanup and atomic restore belong to stage H. */
object TrackerDatabaseFactory {
    // Change only this policy when R1b.4 narrows supported source versions.
    internal val supportedVersions = 1..4

    /** Synchronous I/O: callers use Dispatchers.IO and the dataset mutation/pointer guards.
     * Returning means SQLite opened, Room validated/migrated, and integrity was checked.
     */
    fun open(context: Context, datasetId: UUID): TrackerDatabase {
        try {
            val name = "subscriptions-$datasetId.db"
            val file = context.getDatabasePath(name)
            if (!file.exists()) {
                // A missing DB alongside retained dataset data is not a clean installation.
                val settings = java.io.File(context.filesDir, "datastore/settings-$datasetId.preferences_pb")
                val assets = java.io.File(context.filesDir, "assets/$datasetId")
                if (settings.exists() || assets.exists() || java.io.File(file.path + "-wal").exists() ||
                    java.io.File(file.path + "-shm").exists()) throw StorageUnavailable(StorageFailure.CORRUPT)
            } else {
                // Read-only, no CREATE_IF_NECESSARY. An existing empty/foreign file is not a new install.
                android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                    android.database.DatabaseErrorHandler { throw StorageUnavailable(StorageFailure.CORRUPT) }).use { probe ->
                    probe.rawQuery("PRAGMA quick_check", null).use { result ->
                        if (!result.moveToFirst() || result.getString(0) != "ok") throw StorageUnavailable(StorageFailure.CORRUPT)
                    }
                    if (probe.version !in supportedVersions) throw StorageUnavailable(StorageFailure.INCOMPATIBLE)
                }
            }
            return validatedResource(create = {
                Room.databaseBuilder(context.applicationContext, TrackerDatabase::class.java, name)
                    .openHelperFactory(PreservingOpenHelperFactory()).build()
            }, validate = { db ->
                val opened = db.openHelper.writableDatabase // build() alone is not readiness.
                db.validateOpenedSchema()
                opened.query("PRAGMA quick_check").use { result ->
                    if (!result.moveToFirst() || result.getString(0) != "ok") throw StorageUnavailable(StorageFailure.CORRUPT)
                }
            }, close = { it.close() })
        } catch (error: Exception) { throw storageFailure(error) }
    }
}
