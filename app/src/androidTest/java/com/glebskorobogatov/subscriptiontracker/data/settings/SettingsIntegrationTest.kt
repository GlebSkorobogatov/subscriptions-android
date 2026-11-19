package com.glebskorobogatov.subscriptiontracker.data.settings

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
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
            first.repository.update { it.copy(theme = Theme.DARK, renewalOffsets = setOf(7,0),
                costRankingPeriod = CostRankingPeriod.YEAR) }
            assertEquals(Theme.DARK, shared.repository.settings.first().theme)
            assertEquals(CostRankingPeriod.YEAR, shared.repository.settings.first().costRankingPeriod)
            assertEquals(Settings(), isolated.repository.settings.first())
        } finally { first.close(); shared.close(); isolated.close() }
        val reopened = DatasetSettingsFactory.acquire(context, id)
        try {
            assertEquals(Theme.DARK, reopened.repository.settings.first().theme)
            assertEquals(LocalTime.of(9,0), reopened.repository.settings.first().reminderLocalTime)
            assertEquals(setOf(7,0),reopened.repository.settings.first().renewalOffsets)
            assertEquals(CostRankingPeriod.YEAR, reopened.repository.settings.first().costRankingPeriod)
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
            lease.repository.update { it.copy(renewalOffsets = setOf(0)) }
            assertEquals(setOf(0),updated.await().renewalOffsets)
            try { lease.repository.update { it.copy(renewalOffsets = setOf(2)) }; fail("Invalid offset") }
            catch (_: IllegalArgumentException) { }
            assertEquals(setOf(0),lease.repository.settings.first().renewalOffsets)
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
            val subscription = SubscriptionEntity(id = UUID.randomUUID(), name = "Тест", accountLabel = null, priceMinor = 100, intervalUnit = FrequencyUnit.MONTH, intervalCount = 1, state = SubscriptionState.ACTIVE, anchorYear = 2027, anchorMonth = 1, anchorDay = 31, currentPeriodStartOverride = null, currentPeriodEnd = null, cancelledAccessEndExclusive = null, frozenCancelledPeriodStart = null, logoAssetId = null, catalogLogoId = null, createdAt = now, updatedAt = now, archivedAt = null)
            withContext(Dispatchers.IO) { db.subscriptions().insert(subscription) }
            assertEquals(listOf(subscription), withTimeout(5000) { source.subscriptions.first { it.isNotEmpty() } })
            lease.repository.update { it.copy(renewalOffsets = emptySet()) }
            val settings = source.settings.first()
            assertTrue(com.glebskorobogatov.subscriptiontracker.data.integration.reminderInputs(listOf(subscription),settings).single().renewalOffsets.isEmpty())
        } finally { db.close(); lease.close() }
    }
    @Test fun explicitEmptyGlobalSetStaysDisabledAfterFinalLeaseReopen() = runBlocking {
        val id = UUID.randomUUID()
        var lease = DatasetSettingsFactory.acquire(context,id)
        try {
            lease.repository.update { it.copy(renewalOffsets = setOf(7,0)) }
            lease.close(); lease = DatasetSettingsFactory.acquire(context,id)
            assertEquals(setOf(7,0),lease.repository.settings.first().renewalOffsets)
            lease.repository.update { it.copy(renewalOffsets = emptySet()) }
            lease.close(); lease = DatasetSettingsFactory.acquire(context,id)
            assertTrue(lease.repository.settings.first().renewalOffsets.isEmpty())
        } finally { lease.close(); java.io.File(context.filesDir,"datastore/settings-$id.preferences_pb").delete() }
    }

    @Test fun legacyStoredValuesAreIgnoredAndRemainIntactAfterCurrentWriteAndReopen() = runBlocking {
        val id = UUID.randomUUID()
        val file = context.preferencesDataStoreFile("settings-$id")
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val raw = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope,produceFile = { file })
        val time = androidx.datastore.preferences.core.stringPreferencesKey("reminder_time")
        val currency = androidx.datastore.preferences.core.stringPreferencesKey("chart_currency")
        val horizon = androidx.datastore.preferences.core.intPreferencesKey("chart_months")
        val cancellation = androidx.datastore.preferences.core.stringSetPreferencesKey("cancellation_offsets")
        val offsets = androidx.datastore.preferences.core.stringSetPreferencesKey("payment_offsets")
        raw.updateData { original -> original.toMutablePreferences().apply {
            this[time] = "18:45"; this[currency] = "USD"; this[horizon] = 6
            this[cancellation] = setOf("7","0"); this[offsets] = setOf("7","0","invalid")
        } }
        scope.coroutineContext.job.cancelAndJoin()
        var lease = DatasetSettingsFactory.acquire(context,id)
        try {
            assertEquals(setOf(7,0),lease.repository.settings.first().renewalOffsets)
            assertEquals(LocalTime.of(9,0),lease.repository.settings.first().reminderLocalTime)
            lease.repository.update { it.copy(theme = Theme.DARK) }
            lease.close(); lease = DatasetSettingsFactory.acquire(context,id)
            assertEquals(Theme.DARK,lease.repository.settings.first().theme)
            assertEquals(setOf(7,0),lease.repository.settings.first().renewalOffsets)
        } finally { lease.close() }
        val verifyScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val verify = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = verifyScope,produceFile = { file })
            val retained = verify.data.first()
            assertEquals("18:45",retained[time]); assertEquals("USD",retained[currency]); assertEquals(6,retained[horizon])
            assertEquals(setOf("7","0"),retained[cancellation])
        } finally { verifyScope.coroutineContext.job.cancelAndJoin(); file.delete() }
    }

}
