package com.glebskorobogatov.subscriptiontracker.app

import com.glebskorobogatov.subscriptiontracker.fixtures.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class DatasetCompositionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "d01-${UUID.randomUUID()}")
    private val day = LocalDate.of(2027, 1, 31)
    private val now = Instant.parse("2027-01-31T12:00:00Z")
    private val rub = CurrencyCode.of("RUB")
    private var dataset: UUID? = null
    @Before fun setup() { directory.mkdirs() }
    @After fun cleanup() {
        dataset?.let {
            context.deleteDatabase("subscriptions-$it.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$it.db") }
            File(context.filesDir, "datastore/settings-$it.preferences_pb").delete()
        }
        directory.deleteRecursively()
    }
    @Test fun pointerAndBothStoresSurviveClosingAndReopening() = runBlocking {
        val id = ActiveDataset(directory).getOrCreate().also { dataset = it }
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, id)
        var db = TrackerDatabaseFactory.open(context, id)
        var lease = DatasetSettingsFactory.acquire(context, id)
        try {
            var use = SubscriptionUseCases(db, lease.repository, CurrentDate(day))
            val row = use.createSubscription(SubscriptionDraft(id = UUID.randomUUID(), name = "Сервис", accountLabel = "Семейный", price = RubPrice(49900), schedule = ScheduleEdit(day, Frequency(FrequencyUnit.MONTH, 1))), now)
            use.updateSettings { it.copy(theme = Theme.DARK) }
            db.close(); lease.close()
            val reopened = ActiveDataset(directory).getOrCreate()
            assertEquals(id, reopened)
            db = TrackerDatabaseFactory.open(context, reopened)
            lease = DatasetSettingsFactory.acquire(context, reopened)
            use = SubscriptionUseCases(db, lease.repository, CurrentDate(day))
            assertEquals(row, use.subscriptions.first().single())
            assertEquals(Theme.DARK, use.settings.first().theme)
            assertEquals("Семейный", (subscriptionStates(use).first() as SubscriptionsUiState.Content).active.single().accountLabel)
        } finally { db.close(); lease.close() }
    }
    @Test fun corruptedPointerIsNotReplacedWithANewEmptyDataset() {
        val file = File(directory, "active-generation.pointer")
        file.writeText("broken-pointer")
        repeat(2) {
            try { ActiveDataset(directory).getOrCreate(); fail("Expected invalid pointer") }
            catch (_: StorageUnavailable) { }
            assertEquals("broken-pointer", file.readText())
        }
    }
    @Test fun projectionIgnoresHistoryAndUpdatesFuturePriceAndSchedule() = runBlocking {
        val id = ActiveDataset(directory).getOrCreate().also { dataset = it }
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, id)
        val db = TrackerDatabaseFactory.open(context, id)
        val lease = DatasetSettingsFactory.acquire(context, id)
        try {
            val use = SubscriptionUseCases(db, lease.repository, CurrentDate(day))
            val row = use.createSubscription(SubscriptionDraft(id = UUID.randomUUID(), name = "Сервис", accountLabel = null, price = RubPrice(49900), schedule = ScheduleEdit(day, Frequency(FrequencyUnit.MONTH, 1))), now)
            suspend fun content() = withTimeout(5000) { subscriptionStates(use).first() } as SubscriptionsUiState.Content
            val before = content()
            assertEquals(before, content())
            use.editSubscription(row, SubscriptionEdit(name = row.name, accountLabel = null, price = RubPrice(10000), schedule = ScheduleEdit(day.plusDays(3), Frequency(FrequencyUnit.MONTH, 1))), now)
            val after = content()
            assertEquals(day.plusDays(3), after.active.single().eventDate)
            assertEquals(10000L, after.forecast.single().amountMinor)
        } finally { db.close(); lease.close() }
    }
    @Test fun startupAndResumeRefreshCalendarWithoutCreatingPayments() = runBlocking {
        val id = ActiveDataset(directory).getOrCreate().also { dataset = it }
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, id)
        val db = TrackerDatabaseFactory.open(context, id)
        val lease = DatasetSettingsFactory.acquire(context, id)
        try {
            val use = SubscriptionUseCases(db, lease.repository, CurrentDate(day.minusDays(1)))
            val row = use.createSubscription(SubscriptionDraft(id = UUID.randomUUID(), name = "Сервис", accountLabel = null, price = RubPrice(49900), schedule = ScheduleEdit(day, Frequency(FrequencyUnit.MONTH, 1))), now)
            var current = day
            val refreshes = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val states = kotlinx.coroutines.channels.Channel<SubscriptionsUiState>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val reader = launch { observeSession(use, refreshes.receiveAsFlow(), { current }, { now }).collect { states.send(it) } }
            try {
                val first = withTimeout(5000) { states.receive() } as SubscriptionsUiState.Content
                assertEquals(day, use.date.today.value)
                assertEquals(row.id, first.active.single().id)
                current = day.plusDays(1); refreshes.send(Unit)
                val next = withTimeout(5000) { states.receiveAsFlow().first {
                    (it as? SubscriptionsUiState.Content)?.active?.singleOrNull()?.eventDate == LocalDate.of(2027,2,28)
                } } as SubscriptionsUiState.Content
                assertEquals(SubscriptionState.ACTIVE,next.active.single().status); assertTrue(next.cancelled.isEmpty())
            } finally { reader.cancelAndJoin(); refreshes.close(); states.close() }
        } finally { db.close(); lease.close() }
    }
    @Test fun futureSchemaOpeningFailsWithoutDestructiveMigration() = runBlocking {
        val id = ActiveDataset(directory).getOrCreate().also { dataset = it }
        val file = context.getDatabasePath("subscriptions-$id.db")
        file.parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE sentinel (value TEXT NOT NULL)")
            it.execSQL("INSERT INTO sentinel VALUES ('preserve')")
            it.version = 99
        }
        repeat(2) {
            val failure = assertThrows(StorageUnavailable::class.java) { TrackerDatabaseFactory.open(context, id) }
            assertEquals(StorageFailure.INCOMPATIBLE, failure.failure)
        }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("SELECT value FROM sentinel", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("preserve", cursor.getString(0))
            }
            assertEquals(99, db.version)
        }
    }
    @Test fun sessionShutdownJoinsDetailsReadersBeforeReleasingStores() = runBlocking {
        val id = ActiveDataset(directory).getOrCreate().also { dataset = it }
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, id)
        val source = SubscriptionsComposition(context) { id }
        val ready = CompletableDeferred<Unit>(); val observed = CompletableDeferred<Unit>()
        val root = launch { source.observe(emptyFlow()).collect { ready.complete(Unit) } }
        withTimeout(5000) { ready.await() }
        val reader = launch {
            try { source.details(UUID.randomUUID()).collect { observed.complete(Unit) } }
            catch (_: IllegalStateException) { }
        }
        withTimeout(5000) { observed.await(); root.cancelAndJoin(); reader.join() }
    }
}
