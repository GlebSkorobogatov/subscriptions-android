package com.glebskorobogatov.subscriptiontracker.data.settings

import androidx.datastore.preferences.core.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import java.time.LocalTime
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SettingsTest {
    @Test fun defaultsAndCodecRoundTrip() {
        val defaults = SettingsCodec.read(emptyPreferences())
        assertEquals(Theme.SYSTEM, defaults.theme); assertEquals(LocalTime.of(9, 0), defaults.reminderLocalTime)
        assertEquals(setOf(3, 1), defaults.paymentOffsetsDefault); assertEquals(setOf(7, 3), defaults.cancellationOffsetsDefault)
        assertEquals(3, defaults.chartHorizonMonths); assertNull(defaults.selectedChartCurrency)
        val p = mutablePreferencesOf(); SettingsCodec.write(p, defaults.copy(theme = Theme.DARK, chartHorizonMonths = 12))
        assertEquals(defaults.copy(theme = Theme.DARK, chartHorizonMonths = 12), SettingsCodec.read(p))
    }
    @Test fun remT01T03T04AndDbT06IndependentInheritanceAndCustom() {
        val original = Settings(); val changed = original.copy(paymentOffsetsDefault = setOf(0), cancellationOffsetsDefault = emptySet())
        val custom = ReminderOverrideEntity(UUID.randomUUID(), ReminderEventType.CANCELLATION, ReminderMode.CUSTOM, setOf(7, 3, 1))
        assertEquals(setOf(7, 3, 1), effectiveOffsets(changed, ReminderEventType.CANCELLATION, custom))
        assertEquals(setOf(0), effectiveOffsets(changed, ReminderEventType.PAYMENT, null))
        assertEquals(setOf(7, 3), effectiveOffsets(original, ReminderEventType.CANCELLATION, null))
        assertEquals(setOf(7, 3, 1), custom.offsets)
        assertEquals(emptySet<Int>(), effectiveOffsets(changed, ReminderEventType.CANCELLATION, custom.copy(mode = ReminderMode.INHERIT_GLOBAL)))
    }
    @Test fun remT05T06EmptyCustomDisablesOnlyOneTypeAndZeroIsSupported() {
        val rule = ReminderOverrideEntity(UUID.randomUUID(), ReminderEventType.PAYMENT, ReminderMode.CUSTOM, emptySet())
        assertTrue(effectiveOffsets(Settings(), ReminderEventType.PAYMENT, rule).isEmpty())
        assertEquals(setOf(7, 3), effectiveOffsets(Settings(), ReminderEventType.CANCELLATION, null))
        assertEquals(setOf(0), effectiveOffsets(Settings(), ReminderEventType.PAYMENT, rule.copy(offsets = setOf(0))))
    }
    @Test fun invalidValuesFailExplicitlyWithoutSilentReset() {
        assertThrows(IllegalArgumentException::class.java) { Settings(chartHorizonMonths = 4) }
        assertThrows(IllegalArgumentException::class.java) { Settings(paymentOffsetsDefault = setOf(2)) }
        assertThrows(IllegalArgumentException::class.java) { Settings(cancellationOffsetsDefault = setOf(-1)) }
        assertThrows(IllegalArgumentException::class.java) { Settings(version = 2) }
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.read(mutablePreferencesOf(stringPreferencesKey("theme") to "INVALID")) }
        assertThrows(Exception::class.java) { SettingsCodec.read(mutablePreferencesOf(stringPreferencesKey("reminder_time") to "25:00")) }
    }
    @Test fun emptyOffsetsAndNullableCurrencyPersist() {
        val p = mutablePreferencesOf()
        val value = Settings(paymentOffsetsDefault = emptySet(), selectedChartCurrency = com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode.of("JPY"))
        SettingsCodec.write(p, value); assertEquals(value, SettingsCodec.read(p))
        SettingsCodec.write(p, value.copy(selectedChartCurrency = null)); assertNull(SettingsCodec.read(p).selectedChartCurrency)
    }
}
