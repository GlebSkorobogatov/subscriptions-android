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
                val indices = entity.getJSONArray("indices")
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
}
