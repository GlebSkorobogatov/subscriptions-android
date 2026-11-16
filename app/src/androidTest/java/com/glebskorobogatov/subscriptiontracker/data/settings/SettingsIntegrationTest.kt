package com.glebskorobogatov.subscriptiontracker.data.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Test fun dbT07ReopenAndDatasetIsolationAndSharedLease() = runBlocking {
        val id = UUID.randomUUID(); val other = UUID.randomUUID()
        val first = DatasetSettingsFactory.acquire(context, id)
        val shared = DatasetSettingsFactory.acquire(context, id)
        val isolated = DatasetSettingsFactory.acquire(context, other)
        try {
            assertSame(first.repository, shared.repository)
            assertEquals(Settings(), first.repository.settings.first())
            first.repository.update { it.copy(theme = Theme.DARK, reminderLocalTime = LocalTime.of(18, 45)) }
            assertEquals(Theme.DARK, shared.repository.settings.first().theme)
            assertEquals(Settings(), isolated.repository.settings.first())
        } finally { first.close(); shared.close(); isolated.close() }
        val reopened = DatasetSettingsFactory.acquire(context, id)
        try {
            assertEquals(Theme.DARK, reopened.repository.settings.first().theme)
            assertEquals(LocalTime.of(18, 45), reopened.repository.settings.first().reminderLocalTime)
        } finally { reopened.close() }
    }
    @Test fun reactiveFlowAndInvalidUpdateIsAtomic(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val lease = DatasetSettingsFactory.acquire(context, id)
        val values = kotlinx.coroutines.channels.Channel<Settings>(kotlinx.coroutines.channels.Channel.BUFFERED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            lease.repository.settings.collect { values.send(it) }
        }
        try {
            // Receive the real initial value and keep that same collector subscribed. Cold file
            // initialization is setup, not the timed reactive-write assertion; UNDISPATCHED alone
            // cannot guarantee that a cold DataStore flow has initialized/subscribed.
            assertEquals(Settings(), values.receive())
            val updated = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5000) { values.receive() }
            }
            lease.repository.update { it.copy(chartHorizonMonths = 6) }
            assertEquals(6, updated.await().chartHorizonMonths)
            try { lease.repository.update { it.copy(chartHorizonMonths = 5) }; fail("Invalid horizon") }
            catch (_: IllegalArgumentException) { }
            assertEquals(6, lease.repository.settings.first().chartHorizonMonths)
        } finally { withContext(NonCancellable) {
            observer.cancelAndJoin(); values.close(); lease.close()
            java.io.File(context.filesDir,"datastore/settings-$id.preferences_pb").delete()
        } }
    }
    @Test fun dbT06GlobalChangesDoNotRewriteRoomAndSubscriptionsAreObservable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        val lease = DatasetSettingsFactory.acquire(context, UUID.randomUUID())
        try {
            val source = RoomLocalDataSource(db, lease.repository)
            assertTrue(source.subscriptions.first().isEmpty())
            val now = Instant.parse("2026-10-30T00:00:00Z")
            val subscription = SubscriptionEntity(UUID.randomUUID(), "Тест", null, 100, CurrencyCode.of("RUB"),
                FrequencyUnit.MONTH, 1, RenewalMode.AUTO, LifecycleState.ACTIVE, 2027, 1, 31, 0,
                null, null, null, null, null, null, null, now, now)
            val rule = ReminderOverrideEntity(subscription.id, ReminderEventType.CANCELLATION, ReminderMode.CUSTOM, setOf(0))
            withContext(Dispatchers.IO) { db.subscriptions().insert(subscription); db.reminders().insert(rule) }
            assertEquals(listOf(subscription), withTimeout(5000) { source.subscriptions.first { it.isNotEmpty() } })
            lease.repository.update { it.copy(paymentOffsetsDefault = emptySet(), cancellationOffsetsDefault = setOf(1)) }
            val stored = withContext(Dispatchers.IO) { db.reminders().forSubscription(subscription.id).single() }
            assertEquals(rule, stored)
            val settings = source.settings.first()
            assertEquals(setOf(0), effectiveOffsets(settings, ReminderEventType.CANCELLATION, stored))
            assertTrue(effectiveOffsets(settings, ReminderEventType.PAYMENT, null).isEmpty())
        } finally { db.close(); lease.close() }
    }
}
