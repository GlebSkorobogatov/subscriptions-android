package com.glebskorobogatov.subscriptiontracker.data.settings

import com.glebskorobogatov.subscriptiontracker.data.integration.settingsChangeReconcilesReminders
import com.glebskorobogatov.subscriptiontracker.data.integration.updateStoredSettings
import com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderChanges
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerMotion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SettingsUpdateTest {
    @Test fun themeAndAnimationsDoNotReconcileOffsetsDoAndNeighboursStay() = runTest {
        var signals = 0
        val repository = MemorySettings(Settings(theme = Theme.LIGHT,renewalOffsets = setOf(3,1),animationsEnabled = true))
        val changes = ReminderChanges(request = { signals++ })
        updateStoredSettings(repository,changes) { it.copy(theme = Theme.DARK) }
        updateStoredSettings(repository,changes) { it.copy(animationsEnabled = false) }
        assertEquals(0,signals)
        updateStoredSettings(repository,changes) { it.copy(theme = Theme.SYSTEM,animationsEnabled = true) }
        assertEquals(0,signals)
        val kept = repository.settings.first()
        assertEquals(Theme.SYSTEM,kept.theme)
        assertTrue(kept.animationsEnabled)
        assertEquals(setOf(3,1),kept.renewalOffsets)
        updateStoredSettings(repository,changes) { it.copy(renewalOffsets = emptySet()) }
        assertEquals(1,signals)
        val after = repository.settings.first()
        assertTrue(after.renewalOffsets.isEmpty())
        assertEquals(Theme.SYSTEM,after.theme)
        assertTrue(after.animationsEnabled)
        updateStoredSettings(repository,changes) { it.copy(costRankingPeriod = CostRankingPeriod.YEAR) }
        assertEquals(1, signals)
        assertEquals(after.copy(costRankingPeriod = CostRankingPeriod.YEAR), repository.settings.first())
        assertFalse(settingsChangeReconcilesReminders(kept,kept.copy(theme = Theme.DARK,animationsEnabled = false)))
        assertTrue(settingsChangeReconcilesReminders(kept,kept.copy(renewalOffsets = setOf(0))))
    }

    @Test fun trackerMotionIsInstantWhenDisabledOrSystemScaleIsZero() {
        assertEquals(220,TrackerMotion.durationMillis(true,1f))
        assertEquals(0,TrackerMotion.durationMillis(false,1f))
        assertEquals(0,TrackerMotion.durationMillis(true,0f))
        assertEquals(0,TrackerMotion.durationMillis(false,0f))
        assertEquals(0,TrackerMotion.durationMillis(true,Float.NaN))
    }

    private class MemorySettings(initial: Settings) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: kotlinx.coroutines.flow.Flow<Settings> = state
        override suspend fun update(transform: (Settings) -> Settings) { state.value = transform(state.value) }
    }
}
