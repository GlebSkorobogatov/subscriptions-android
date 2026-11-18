package com.glebskorobogatov.subscriptiontracker.data.settings

import androidx.datastore.core.DataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DataStore remains the stored value. A collector can miss one confirmation and then reread it. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesSettingsRepositoryTest {
    @Test fun storeEmissionBeforeTheRefreshStaysAuthoritative() = runTest {
        val store = GateStore()
        val repository = PreferencesSettingsRepository(store)
        val seen = mutableListOf<Settings>()
        val reader = launch { repository.settings.collect { seen += it } }
        testScheduler.advanceUntilIdle()
        repository.update { it.copy(theme = Theme.DARK, animationsEnabled = false, renewalOffsets = setOf(7, 1)) }
        testScheduler.advanceUntilIdle()
        val value = seen.last()
        assertEquals(Theme.DARK, value.theme)
        assertFalse(value.animationsEnabled)
        assertEquals(setOf(7, 1), value.renewalOffsets)
        assertEquals(value, SettingsCodec.read(store.latest.value))
        reader.cancel()
    }

    @Test fun missedConfirmationIsRereadAndDoesNotBlockTheNextValue() = runTest {
        val store = GateStore()
        val repository = PreferencesSettingsRepository(store)
        val seen = mutableListOf<Settings>()
        val reader = launch { repository.settings.collect { seen += it } }
        testScheduler.advanceUntilIdle()
        store.dropUpdates = true
        repository.update { it.copy(theme = Theme.DARK) }
        testScheduler.advanceUntilIdle()
        assertEquals(Theme.DARK, seen.last().theme)
        repository.update { it.copy(theme = Theme.LIGHT, animationsEnabled = false, renewalOffsets = setOf(0)) }
        testScheduler.advanceUntilIdle()
        val value = seen.last()
        assertEquals(Theme.LIGHT, value.theme)
        assertFalse(value.animationsEnabled)
        assertEquals(setOf(0), value.renewalOffsets)
        assertEquals(value, SettingsCodec.read(store.latest.value))
        reader.cancel()
    }

    @Test fun sequentialWritesKeepThemeAnimationsAndOffsets() = runTest {
        val repository = PreferencesSettingsRepository(GateStore())
        repository.update { it.copy(theme = Theme.DARK) }
        repository.update { it.copy(animationsEnabled = false) }
        repository.update { it.copy(renewalOffsets = setOf(7, 3, 1)) }
        val value = repository.settings.first()
        assertEquals(Theme.DARK, value.theme)
        assertFalse(value.animationsEnabled)
        assertEquals(setOf(7, 3, 1), value.renewalOffsets)
    }

    @Test fun concurrentWritesKeepIndependentFields() = runTest {
        val repository = PreferencesSettingsRepository(GateStore())
        launch { repository.update { it.copy(theme = Theme.DARK) } }
        launch { repository.update { it.copy(animationsEnabled = false) } }
        launch { repository.update { it.copy(renewalOffsets = setOf(0)) } }
        testScheduler.advanceUntilIdle()
        val value = repository.settings.first()
        assertEquals(Theme.DARK, value.theme)
        assertFalse(value.animationsEnabled)
        assertEquals(setOf(0), value.renewalOffsets)
    }

    @Test fun cancellationAfterCommitLeavesTheStoredValueReadable() = runTest {
        val store = CommitThenSuspendStore()
        val repository = PreferencesSettingsRepository(store)
        val seen = mutableListOf<Settings>()
        val reader = launch { repository.settings.collect { seen += it } }
        testScheduler.advanceUntilIdle()
        val writer = launch { repository.update { it.copy(theme = Theme.DARK, renewalOffsets = setOf(7)) } }
        store.committed.await()
        writer.cancelAndJoin()
        testScheduler.advanceUntilIdle()
        assertEquals(Theme.DARK, seen.last().theme)
        assertEquals(setOf(7), seen.last().renewalOffsets)
        assertEquals(seen.last(), repository.settings.first())
        reader.cancel()
    }

    @Test fun collectorOpenedAfterCommitReadsTheStore() = runTest {
        val repository = PreferencesSettingsRepository(GateStore())
        repository.update { it.copy(theme = Theme.LIGHT, animationsEnabled = false) }
        val value = repository.settings.first()
        assertEquals(Theme.LIGHT, value.theme)
        assertFalse(value.animationsEnabled)
    }

    @Test fun readFailureIsNotReplacedByThePreviousValue() = runTest {
        val store = GateStore()
        val repository = PreferencesSettingsRepository(store)
        repository.update { it.copy(theme = Theme.DARK) }
        val seen = mutableListOf<Settings>()
        var thrown: Throwable? = null
        val reader = launch {
            try { repository.settings.collect { seen += it } }
            catch (error: Throwable) { thrown = error }
        }
        testScheduler.advanceUntilIdle()
        assertEquals(Theme.DARK, seen.last().theme)
        store.failReads = true
        repository.update { it.copy(theme = Theme.LIGHT) }
        testScheduler.advanceUntilIdle()
        assertTrue(thrown is IOException)
        assertEquals(Theme.DARK, seen.last().theme)
        assertEquals(Theme.LIGHT, SettingsCodec.read(store.latest.value).theme)
        reader.cancel()
    }

    @Test fun retryAfterReadFailureReadsTheCommittedStore() = runTest {
        val store = GateStore()
        val repository = PreferencesSettingsRepository(store)
        repository.update { it.copy(theme = Theme.DARK, animationsEnabled = false, renewalOffsets = setOf(3)) }
        store.failReads = true
        try {
            repository.settings.first()
            fail("unreadable settings must not return a value")
        } catch (error: Throwable) {
            assertTrue(error is IOException)
        }
        store.failReads = false
        val value = repository.settings.first()
        assertEquals(Theme.DARK, value.theme)
        assertFalse(value.animationsEnabled)
        assertEquals(setOf(3), value.renewalOffsets)
    }

    @Test fun rejectedTransformDoesNotReplaceTheCommittedSettings() = runTest {
        val repository = PreferencesSettingsRepository(GateStore())
        repository.update { it.copy(theme = Theme.DARK) }
        try {
            repository.update { error("rejected") }
            fail("the transform must fail")
        } catch (error: IllegalStateException) {
            assertEquals("rejected", error.message)
        }
        assertEquals(Theme.DARK, repository.settings.first().theme)
    }

    private class GateStore : DataStore<Preferences> {
        val latest = MutableStateFlow(emptyPreferences())
        var dropUpdates = false
        var failReads = false
        override val data: Flow<Preferences> = flow {
            if (failReads) throw IOException("settings unreadable")
            val start = latest.value
            emit(start)
            latest.collect { value ->
                if (failReads) throw IOException("settings unreadable")
                if (!dropUpdates && value != start) emit(value)
            }
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(latest.value)
            latest.value = next
            return next
        }
    }

    private class CommitThenSuspendStore : DataStore<Preferences> {
        private val latest = MutableStateFlow(emptyPreferences())
        val committed = CompletableDeferred<Unit>()
        override val data: Flow<Preferences> = flow {
            emit(latest.value)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(latest.value)
            latest.value = next
            committed.complete(Unit)
            awaitCancellation()
        }
    }
}
