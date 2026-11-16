package com.glebskorobogatov.subscriptiontracker.data.room

import android.database.sqlite.*
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Room must keep its callbacks, but neither SQLite's corruption callback nor helper recovery may delete files. */
internal class PreservingOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                // Do not delegate to the default implementation: it deletes attached databases.
                throw StorageUnavailable(StorageFailure.CORRUPT)
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(false).build())
    }
}

internal fun storageFailure(error: Exception): StorageUnavailable {
    if (error is CancellationException) throw error
    if (error is StorageUnavailable) return error
    val failure = when (error) {
        is SQLiteDatabaseCorruptException -> StorageFailure.CORRUPT
        is SQLiteDiskIOException, is SQLiteFullException, is SQLiteDatabaseLockedException,
        is SQLiteTableLockedException, is SQLiteCantOpenDatabaseException, is IOException -> StorageFailure.TEMPORARY
        // These are Room's specific validation diagnostics, not arbitrary IllegalStateExceptions.
        is IllegalStateException -> if (listOf("Room cannot verify the data integrity", "Migration didn't properly handle",
                "Pre-packaged database has an invalid schema", "A migration from ").any { error.message?.startsWith(it) == true })
            StorageFailure.INCOMPATIBLE else StorageFailure.UNKNOWN
        is SQLiteException -> if (error.message?.contains("file is not a database", ignoreCase = true) == true)
            StorageFailure.CORRUPT else StorageFailure.UNKNOWN
        else -> StorageFailure.UNKNOWN
    }
    return StorageUnavailable(failure)
}

/** Releases a partially initialized resource, preserving cancellation and the original safe failure. */
internal inline fun <T> validatedResource(create: () -> T, validate: (T) -> Unit, close: (T) -> Unit): T {
    val resource = create()
    try { validate(resource); return resource }
    catch (error: Throwable) {
        try { close(resource) } catch (_: Exception) { /* Do not replace the opening failure. */ }
        throw error
    }
}
