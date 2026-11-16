package com.glebskorobogatov.subscriptiontracker.data.room

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.glebskorobogatov.subscriptiontracker.app.*
import com.glebskorobogatov.subscriptiontracker.background.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Every file lives below a disposable synthetic fixture root, never the app's active dataset. */
class StorageOpeningTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var id: UUID
    private val subscription = UUID.randomUUID()
    private lateinit var pointer: File
    private lateinit var settings: File
    private lateinit var image: File
    private val dbFile get() = context.getDatabasePath("subscriptions-$id.db")
    @Before fun fixture() {
        root = File(base.cacheDir, "storage-fixture-${UUID.randomUUID()}").also { it.mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").also { it.mkdirs() }
            override fun getCacheDir() = File(root, "cache").also { it.mkdirs() }
            override fun getDatabasePath(name: String) = File(File(root, "databases").also { it.mkdirs() }, name)
        }
        id = ActiveDataset(context.filesDir).getOrCreate()
        pointer = File(context.filesDir, "active-generation.pointer")
        settings = File(context.filesDir, "datastore/settings-$id.preferences_pb").also {
            it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(10, 0))
        }
        image = File(context.filesDir, "assets/$id/images/fixture.png").also {
            it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1, 7, 3, 9)); it.setLastModified(1)
        }
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun preserved() {
        assertEquals(id.toString(), pointer.readText())
        assertArrayEquals(byteArrayOf(10, 0), settings.readBytes())
        assertArrayEquals(byteArrayOf(1, 7, 3, 9), image.readBytes())
    }
    private fun fixtureDatabase(version: Int) {
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open(
            "com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabase/$version.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(schema.getString("identityHash")))
            db.execSQL("INSERT INTO subscriptions(id,name,priceMinor,currencyCode,intervalUnit,intervalCount,renewalMode,lifecycleState,anchorYear,anchorMonth,anchorDay,nextCycleIndex,createdAt,updatedAt) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(subscription.toString(), "Синтетическая запись", 12345, "RUB", "MONTH", 1, "AUTO", "ACTIVE", 2027, 1, 31, 0, "2027-01-01T00:00:00Z", "2027-01-01T00:00:00Z"))
            db.version = version
        }
    }
    private fun assertRecordStillReadable(version: Int) {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(version, db.version)
            db.rawQuery("SELECT name,priceMinor FROM subscriptions WHERE id=?", arrayOf(subscription.toString())).use {
                assertTrue(it.moveToFirst()); assertEquals("Синтетическая запись", it.getString(0)); assertEquals(12345L, it.getLong(1))
            }
        }
        preserved()
    }
    @Test fun freshInstallReturnsActuallyOpenValidatedRoom() {
        settings.delete(); File(context.filesDir, "assets/$id").deleteRecursively()
        TrackerDatabaseFactory.open(context, id).let { db ->
            try { assertTrue(db.isOpen); assertEquals(4, db.openHelper.writableDatabase.version); assertTrue(db.subscriptions().all().isEmpty()) }
            finally { db.close() }
        }
        assertEquals(id.toString(), pointer.readText())
        assertFalse(settings.exists()); assertFalse(image.exists())
    }
    @Test fun versionsOneThroughFourPreserveRowsAndMigrateToV4() {
        for (version in 1..4) {
            // Only this test's synthetic database is replaced between fixtures.
            dbFile.delete(); File(dbFile.path + "-wal").delete(); File(dbFile.path + "-shm").delete()
            fixtureDatabase(version)
            val db = TrackerDatabaseFactory.open(context, id)
            try { assertTrue(db.isOpen); assertEquals(12345L, db.subscriptions().find(subscription)!!.priceMinor) }
            finally { db.close() }
            assertRecordStillReadable(4)
        }
    }
    @Test fun unsupportedVersionNeverReplacesDataOrChangesOtherStores() {
        fixtureDatabase(4)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { it.version = 99 }
        val bytes = dbFile.readBytes()
        repeat(2) {
            assertEquals(StorageFailure.INCOMPATIBLE, assertThrows(StorageUnavailable::class.java) {
                TrackerDatabaseFactory.open(context, id)
            }.failure)
            assertArrayEquals(bytes, dbFile.readBytes()); assertRecordStillReadable(99)
        }
    }
    @Test fun matchingIdentityHashDoesNotHideInvalidSchema() {
        fixtureDatabase(4)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { it.execSQL("ALTER TABLE subscriptions ADD COLUMN fixture_extra TEXT") }
        repeat(2) {
            assertEquals(StorageFailure.INCOMPATIBLE, assertThrows(StorageUnavailable::class.java) {
                TrackerDatabaseFactory.open(context, id)
            }.failure)
            assertRecordStillReadable(4)
        }
    }
    @Test fun corruptFileAndPointerAreNeverRecreated() {
        val corrupt = ByteArray(4096) { 73 }
        dbFile.writeBytes(corrupt)
        assertEquals(StorageFailure.CORRUPT, assertThrows(StorageUnavailable::class.java) {
            TrackerDatabaseFactory.open(context, id)
        }.failure)
        assertArrayEquals(corrupt, dbFile.readBytes()); preserved()
        pointer.writeText("invalid-synthetic-pointer")
        repeat(2) {
            assertEquals(StorageFailure.INVALID_POINTER, assertThrows(StorageUnavailable::class.java) {
                ActiveDataset(context.filesDir).getOrCreate()
            }.failure)
            assertEquals("invalid-synthetic-pointer", pointer.readText())
            assertArrayEquals(corrupt, dbFile.readBytes())
            assertArrayEquals(byteArrayOf(10, 0), settings.readBytes())
            assertArrayEquals(byteArrayOf(1, 7, 3, 9), image.readBytes())
        }
    }
    @Test fun missingDatabaseWithRetainedSettingsImagesOrWalIsNotANewInstall() {
        assertEquals(StorageFailure.CORRUPT, assertThrows(StorageUnavailable::class.java) {
            TrackerDatabaseFactory.open(context, id)
        }.failure)
        assertFalse(dbFile.exists()); preserved()
        settings.delete(); File(context.filesDir, "assets/$id").deleteRecursively()
        val wal = File(dbFile.path + "-wal").also { it.writeBytes(byteArrayOf(5, 6, 7)) }
        val shm = File(dbFile.path + "-shm").also { it.writeBytes(byteArrayOf(8, 9)) }
        assertEquals(StorageFailure.CORRUPT, assertThrows(StorageUnavailable::class.java) {
            TrackerDatabaseFactory.open(context, id)
        }.failure)
        assertFalse(dbFile.exists()); assertArrayEquals(byteArrayOf(5, 6, 7), wal.readBytes())
        assertArrayEquals(byteArrayOf(8, 9), shm.readBytes()); assertEquals(id.toString(), pointer.readText())
    }
    @Test fun missingPointerWithRetainedDataDoesNotCreateNewGeneration() {
        pointer.delete()
        assertEquals(StorageFailure.INVALID_POINTER, assertThrows(StorageUnavailable::class.java) {
            ActiveDataset(context.filesDir).getOrCreate()
        }.failure)
        assertFalse(pointer.exists()); assertTrue(image.exists()); assertTrue(settings.exists())
    }
    @Test fun failedCompositionDoesNotPublishUseCasesSweepImagesOrReconcile() = runBlocking {
        fixtureDatabase(4)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { it.version = 99 }
        var reconciles = 0
        val source = SubscriptionsComposition(context, reconcile = { reconciles++ })
        try { source.observe(emptyFlow()).first(); fail("Must block") } catch (error: StorageUnavailable) {
            assertEquals(StorageFailure.INCOMPATIBLE, error.failure)
        }
        assertNull(source.activeDatasetId()); assertEquals(0, reconciles)
        var invoked = false
        try { source.perform { invoked = true }; fail("No session") } catch (_: IllegalStateException) { }
        assertFalse(invoked); assertRecordStillReadable(99) // Includes the old unreferenced image: sweep was not run.
    }
    @Test fun failedBackgroundOpenDoesNotAcquireSettingsMutateLedgerOrPublish() = runBlocking {
        fixtureDatabase(4)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { it.version = 99 }
        val before = dbFile.readBytes()
        var scheduled = 0; var publications = 0; var acquired = false
        val scheduler = object : ReminderWorkScheduler {
            override suspend fun requestReconcile() { scheduled++ }
            override suspend fun apply(generation: UUID, tasks: List<ScheduledReminder>, wake: Instant) { scheduled++ }
        }
        val runtime = ReminderRuntime(context, scheduler,
            publisher = com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderPublisher { _, _ -> publications++; com.glebskorobogatov.subscriptiontracker.data.reminders.PublicationResult.PUBLISHED },
            acquireSettings = { _, _ -> acquired = true; error("Must not acquire") })
        try { runtime.process(id, subscription, "a".repeat(64)); fail("Must fail") }
        catch (error: StorageUnavailable) { assertEquals(StorageFailure.INCOMPATIBLE, error.failure) }
        assertFalse(acquired); assertEquals(0, publications); assertEquals(0, scheduled)
        assertArrayEquals(before, dbFile.readBytes()); assertRecordStillReadable(99)
    }
    @Test fun staleCompositionCannotPerformAgainstNewGeneration() = runBlocking {
        // Valid default settings instead of the opaque settings bytes used by failure fixtures.
        settings.delete()
        fixtureDatabase(4)
        val source = SubscriptionsComposition(context)
        val ready = CompletableDeferred<Unit>()
        val reader = launch { source.observe(emptyFlow()).collect { ready.complete(Unit) } }
        try {
            withTimeout(5000) { ready.await() }
            val newer = UUID.randomUUID()
            pointer.writeText(newer.toString()) // Synthetic replacement; no production activation is performed.
            var invoked = false
            try { source.perform { invoked = true }; fail("Stale") }
            catch (error: StorageUnavailable) { assertEquals(StorageFailure.STALE_GENERATION, error.failure) }
            assertFalse(invoked)
            assertFalse(context.getDatabasePath("subscriptions-$newer.db").exists())
        } finally { withTimeout(5000) { reader.cancelAndJoin() } }
    }
    private fun journal() = InitializationJournal(context.filesDir, id)
    private fun marker(suffix: String = "state") = File(context.filesDir, "${InitializationJournal.DIRECTORY}/$id.$suffix")
    private fun removeOtherStores() { settings.delete(); File(context.filesDir, "assets/$id").deleteRecursively() }

    @Test fun legacyV4WithoutMarkerGetsReadyOnlyAfterPreservingExistingRows() {
        fixtureDatabase(4); marker().delete()
        val db = TrackerDatabaseFactory.open(context, id)
        try { assertEquals(12345L, db.subscriptions().find(subscription)!!.priceMinor) } finally { db.close() }
        assertEquals(InitializationState.READY, journal().locked { read() }); assertRecordStillReadable(4)
    }
    @Test fun lostReadyDatabaseWithoutSettingsOrImagesCannotBecomeEmptyInstall() {
        removeOtherStores()
        TrackerDatabaseFactory.open(context, id).close()
        assertEquals(InitializationState.READY, journal().locked { read() })
        dbFile.delete()
        val bytes = marker().readBytes()
        repeat(2) {
            assertEquals(StorageFailure.LOST_DATABASE, assertThrows(StorageUnavailable::class.java) {
                TrackerDatabaseFactory.open(context, id)
            }.failure)
            assertFalse(dbFile.exists()); assertArrayEquals(bytes, marker().readBytes())
        }
    }
    @Test fun legacyPointerAloneDoesNotGrantCreationAuthority() {
        removeOtherStores(); marker().delete()
        assertEquals(StorageFailure.INITIALIZATION_UNCERTAIN, assertThrows(StorageUnavailable::class.java) {
            TrackerDatabaseFactory.open(context, id)
        }.failure)
        assertFalse(dbFile.exists()); assertFalse(marker().exists()); assertEquals(id.toString(), pointer.readText())
    }
    @Test fun damagedMarkerIsNotReplacedEvenWithCompatibleDatabase() {
        fixtureDatabase(4); marker().writeText("broken-fixture-marker")
        val before = dbFile.readBytes()
        assertEquals(StorageFailure.INITIALIZATION_UNCERTAIN, assertThrows(StorageUnavailable::class.java) {
            TrackerDatabaseFactory.open(context, id)
        }.failure)
        assertEquals("broken-fixture-marker", marker().readText()); assertArrayEquals(before, dbFile.readBytes())
        assertRecordStillReadable(4)
    }
    @Test fun crashBeforePointerPublicationDoesNotAllocateReplacementGeneration() {
        val before = marker().readBytes(); pointer.delete()
        assertThrows(StorageUnavailable::class.java) { ActiveDataset(context.filesDir).getOrCreate() }
        assertFalse(pointer.exists()); assertFalse(dbFile.exists()); assertArrayEquals(before, marker().readBytes())
    }
    @Test fun crashBeforeRoomAfterConsumingPermissionBlocksSecondCreation() {
        removeOtherStores(); journal().locked { consumeCreation() }
        assertEquals(StorageFailure.INITIALIZATION_UNCERTAIN, assertThrows(StorageUnavailable::class.java) {
            TrackerDatabaseFactory.open(context, id)
        }.failure)
        assertFalse(dbFile.exists()); assertEquals(InitializationState.CREATING, journal().locked { read() })
    }
    @Test fun interruptedAtomicTransitionBlocksOldAuthorizationReuse() {
        removeOtherStores()
        val before = marker().readBytes(); marker("next").writeText(InitializationRecord.encode(id, InitializationState.CREATING))
        assertThrows(StorageUnavailable::class.java) { TrackerDatabaseFactory.open(context, id) }
        assertFalse(dbFile.exists()); assertArrayEquals(before, marker().readBytes()); assertTrue(marker("next").exists())
    }
    @Test fun crashAfterEmptyFileCreationCannotRestartFreshCreation() {
        removeOtherStores(); journal().locked { consumeCreation() }; dbFile.writeBytes(byteArrayOf())
        assertThrows(StorageUnavailable::class.java) { TrackerDatabaseFactory.open(context, id) }
        assertArrayEquals(byteArrayOf(), dbFile.readBytes()); assertEquals(InitializationState.CREATING, journal().locked { read() })
    }
    @Test fun crashAfterValidCreationBeforeReadyRecoversByValidationNotRecreation() {
        journal().locked { consumeCreation() }; fixtureDatabase(4)
        val db = TrackerDatabaseFactory.open(context, id)
        try { assertEquals(12345L, db.subscriptions().find(subscription)!!.priceMinor) } finally { db.close() }
        assertEquals(InitializationState.READY, journal().locked { read() }); assertRecordStillReadable(4)
    }
    @Test fun twoConcurrentOpenersConsumeOneCreationPermission() = runBlocking {
        removeOtherStores()
        val start = CompletableDeferred<Unit>()
        val jobs = List(2) { async(Dispatchers.IO) { start.await(); TrackerDatabaseFactory.open(context, id) } }
        start.complete(Unit)
        val opened = jobs.awaitAll()
        try { opened.forEach { assertTrue(it.isOpen); assertTrue(it.subscriptions().all().isEmpty()) } }
        finally { opened.forEach { it.close() } }
        assertEquals(InitializationState.READY, journal().locked { read() })
    }

    private fun rejectionBeforeCallbacks(version: Int, alter: (SQLiteDatabase) -> Unit) {
        fixtureDatabase(version)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use(alter)
        val before = dbFile.readBytes()
        var created = 0; var opened = 0; var migrated = 0
        assertEquals(StorageFailure.INCOMPATIBLE, assertThrows(StorageUnavailable::class.java) {
            TrackerDatabaseFactory.open(context, id) { fresh ->
                val builder = androidx.room.Room.databaseBuilder(context, TrackerDatabase::class.java, dbFile.name)
                    .openHelperFactory(PreservingOpenHelperFactory(fresh))
                    .addCallback(object : androidx.room.RoomDatabase.Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) { created++ }
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) { opened++ }
                    })
                if (version < 4) builder.addMigrations(object : androidx.room.migration.Migration(version, 4) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { migrated++; error("Must never migrate an invalid source") }
                })
                builder.build()
            }
        }.failure)
        assertEquals(0, created); assertEquals(0, opened); assertEquals(0, migrated)
        assertArrayEquals(before, dbFile.readBytes()); assertRecordStillReadable(version)
    }
    @Test fun changedV4WithOriginalIdentityIsRejectedBeforeWritableCallbacksAndPreservesBytes() =
        rejectionBeforeCallbacks(4) { it.execSQL("ALTER TABLE subscriptions ADD COLUMN unexpected TEXT") }
    @Test fun changedV3WithOriginalIdentityIsRejectedBeforeMigrationAndPreservesBytes() =
        rejectionBeforeCallbacks(3) { it.execSQL("ALTER TABLE subscriptions ADD COLUMN unexpected TEXT") }
    @Test fun changedHistoricalIndexIsRejectedWithoutMigration() =
        rejectionBeforeCallbacks(3) { it.execSQL("DROP INDEX index_subscriptions_lifecycleState") }
    @Test fun changedHistoricalDefaultIsRejectedWithoutMigration() =
        rejectionBeforeCallbacks(3) {
            it.execSQL("PRAGMA writable_schema=ON") // Isolated fixture: alter its declared default without changing rows.
            it.execSQL("UPDATE sqlite_master SET sql=replace(sql,?,?) WHERE name='payments'",
                arrayOf("`requestPayload` TEXT DEFAULT NULL", "`requestPayload` TEXT DEFAULT 'fixture'"))
            it.execSQL("PRAGMA writable_schema=OFF")
        }
    @Test fun partialIndexWithOriginalColumnsIsRejectedWithoutMigration() =
        rejectionBeforeCallbacks(3) {
            it.execSQL("DROP INDEX index_subscriptions_lifecycleState")
            it.execSQL("CREATE INDEX index_subscriptions_lifecycleState ON subscriptions(lifecycleState) WHERE priceMinor>1")
        }
    @Test fun wrongIdentityHashIsRejectedWithoutRewritingMetadata() =
        rejectionBeforeCallbacks(3) { it.execSQL("UPDATE room_master_table SET identity_hash='fixture-wrong'") }

    @Test fun committedWalSchemaIsInspectedAndNotIgnoredOrCheckpointedOnRejection() {
        fixtureDatabase(4)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { writer ->
            assertTrue(writer.enableWriteAheadLogging())
            writer.execSQL("PRAGMA wal_autocheckpoint=0")
            writer.execSQL("UPDATE subscriptions SET priceMinor=777")
            writer.execSQL("ALTER TABLE subscriptions ADD COLUMN wal_only_extra TEXT")
            val wal = File(dbFile.path + "-wal"); val shm = File(dbFile.path + "-shm")
            assertTrue(wal.length() > 0); assertTrue(shm.exists())
            val mainBefore = dbFile.readBytes(); val walBefore = wal.readBytes()
            assertEquals(StorageFailure.INCOMPATIBLE, assertThrows(StorageUnavailable::class.java) {
                TrackerDatabaseFactory.open(context, id)
            }.failure)
            // Writer is deliberately idle and held open: no promise about SHM lock bytes with active connections.
            assertArrayEquals(mainBefore, dbFile.readBytes()); assertArrayEquals(walBefore, wal.readBytes()); assertTrue(shm.exists())
            writer.rawQuery("SELECT priceMinor FROM subscriptions", null).use { assertTrue(it.moveToFirst()); assertEquals(777L, it.getLong(0)) }
            preserved()
        }
    }
    @Test fun compatibleWalCommittedRowsSurvivePreflightAndRoomOpen() {
        fixtureDatabase(4)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { writer ->
            assertTrue(writer.enableWriteAheadLogging()); writer.execSQL("PRAGMA wal_autocheckpoint=0")
            writer.execSQL("UPDATE subscriptions SET priceMinor=888")
            val wal = File(dbFile.path + "-wal"); assertTrue(wal.length() > 0)
            val db = TrackerDatabaseFactory.open(context, id)
            try { assertEquals(888L, db.subscriptions().find(subscription)!!.priceMinor) } finally { db.close() }
            writer.rawQuery("SELECT priceMinor FROM subscriptions", null).use { assertTrue(it.moveToFirst()); assertEquals(888L, it.getLong(0)) }
            assertTrue(wal.exists()); preserved()
        }
    }
    @Test fun hotRollbackJournalIsNotRecoveredByReadOnlyDiagnostics() {
        fixtureDatabase(4)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { writer ->
            writer.execSQL("PRAGMA cache_size=1")
            writer.beginTransaction()
            try {
                writer.execSQL("UPDATE subscriptions SET name=?", arrayOf("X".repeat(200000)))
                val sourceJournal = File(dbFile.path + "-journal"); assertTrue(sourceJournal.length() > 512)
                val hotId = UUID.randomUUID(); val hot = context.getDatabasePath("subscriptions-$hotId.db")
                val hotJournal = File(hot.path + "-journal")
                hot.writeBytes(dbFile.readBytes()); hotJournal.writeBytes(sourceJournal.readBytes())
                // Require an actual hot SQLite journal header; do not pretend an empty journal exercises recovery.
                assertArrayEquals(byteArrayOf(0xd9.toByte(), 0xd5.toByte(), 5, 0xf9.toByte(), 0x20, 0xa1.toByte(), 0x63, 0xd7.toByte()), hotJournal.readBytes().take(8).toByteArray())
                val mainBefore = hot.readBytes(); val journalBefore = hotJournal.readBytes()
                assertThrows(StorageUnavailable::class.java) { TrackerDatabaseFactory.open(context, hotId) }
                assertArrayEquals(mainBefore, hot.readBytes()); assertArrayEquals(journalBefore, hotJournal.readBytes())
                preserved()
            } finally { writer.endTransaction() } // Roll back only this fixture's live writer.
        }
    }

    @Test fun supportedMigrationFailureRollsBackSchemaVersionAndRows() {
        fixtureDatabase(3)
        assertThrows(StorageUnavailable::class.java) {
            TrackerDatabaseFactory.open(context, id) { fresh ->
                androidx.room.Room.databaseBuilder(context, TrackerDatabase::class.java, dbFile.name)
                    .openHelperFactory(PreservingOpenHelperFactory(fresh))
                    .addMigrations(object : androidx.room.migration.Migration(3, 4) {
                        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL("ALTER TABLE subscriptions ADD COLUMN aborted_fixture TEXT")
                            db.execSQL("UPDATE subscriptions SET priceMinor=1")
                            error("Synthetic migration interruption")
                        }
                    }).build()
            }
        }
        assertRecordStillReadable(3)
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA table_info(subscriptions)", null).use {
                while (it.moveToNext()) assertNotEquals("aborted_fixture", it.getString(1))
            }
        }
    }

}
