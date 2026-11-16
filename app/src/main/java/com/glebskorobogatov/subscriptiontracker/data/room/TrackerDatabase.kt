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
    internal fun validateSchema(connection: androidx.sqlite.SQLiteConnection) {
        val delegate = createOpenDelegate() as RoomOpenDelegate
        val result = delegate.onValidateSchema(connection)
        if (!result.isValid) throw StorageUnavailable(StorageFailure.INCOMPATIBLE)
    }
    @android.annotation.SuppressLint("RestrictedApi") // Both hashes are accepted by this pinned Room delegate.
    internal fun schemaHashes(): Set<String> = (createOpenDelegate() as RoomOpenDelegate).let {
        setOf(it.identityHash, it.legacyIdentityHash)
    }

}

/** Explicit dataset selection; activation, file cleanup and atomic restore belong to stage H. */
object TrackerDatabaseFactory {
    // Change only this policy when R1b.4 narrows supported source versions.
    internal val supportedVersions = 1..4

    /** Synchronous I/O: callers use Dispatchers.IO and the dataset mutation/pointer guards.
     * Returning means SQLite opened, Room validated/migrated, and integrity was checked.
     */
    fun open(context: Context, datasetId: UUID): TrackerDatabase = open(context, datasetId) { fresh ->
        Room.databaseBuilder(context.applicationContext, TrackerDatabase::class.java, "subscriptions-$datasetId.db")
            .openHelperFactory(PreservingOpenHelperFactory(allowCreate = fresh)).build()
    }

    /** Test seam changes only the lazy Room builder; all production safety phases are shared. */
    internal fun open(context: Context, datasetId: UUID, createRoom: (Boolean) -> TrackerDatabase): TrackerDatabase {
        try {
            return InitializationJournal(context.filesDir, datasetId).locked {
                val name = "subscriptions-$datasetId.db"
                val file = context.getDatabasePath(name)
                val state = read() // Corrupt or interrupted metadata is never overwritten.
                val exists = file.exists()
                if (!exists) {
                    InitializationRecord.mayCreate(state)
                    val settings = java.io.File(context.filesDir, "datastore/settings-$datasetId.preferences_pb")
                    val assets = java.io.File(context.filesDir, "assets/$datasetId")
                    if (settings.exists() || assets.exists() || java.io.File(file.path + "-wal").exists() ||
                        java.io.File(file.path + "-shm").exists() || java.io.File(file.path + "-journal").exists())
                        throw StorageUnavailable(StorageFailure.CORRUPT)
                    consumeCreation() // Durable, one-use right consumed BEFORE any create-capable open.
                }
                if (exists && state == InitializationState.AUTHORIZED) consumeCreation()
                validatedResource(create = { createRoom(!exists) }, validate = { db ->
                    // build() is lazy: generated validation below must not access openHelper.
                    if (exists) SchemaPreflight.validate(context, file, db::validateSchema, db.schemaHashes())
                    val opened = db.openHelper.writableDatabase
                    db.validateSchema(androidx.sqlite.driver.SupportSQLiteConnection(opened))
                    opened.query("PRAGMA quick_check").use { result ->
                        if (!result.moveToFirst() || result.getString(0) != "ok") throw StorageUnavailable(StorageFailure.CORRUPT)
                    }
                    ready() // Markerless legacy databases get READY only after both validation phases.
                }, close = { it.close() })
            }
        } catch (error: Exception) { throw storageFailure(error) }
    }
}
