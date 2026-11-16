package com.glebskorobogatov.subscriptiontracker.data.settings

import androidx.datastore.preferences.core.*
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class SettingsTest {
    @Test fun defaultsAndCodecRoundTrip() {
        val defaults = SettingsCodec.read(emptyPreferences())
        assertEquals(Theme.SYSTEM,defaults.theme); assertEquals(LocalTime.of(9,0),defaults.reminderLocalTime)
        assertEquals(setOf(3,1),defaults.renewalOffsets)
        val p = mutablePreferencesOf(); SettingsCodec.write(p,defaults.copy(theme = Theme.DARK))
        assertEquals(defaults.copy(theme = Theme.DARK),SettingsCodec.read(p))
    }

    @Test fun allArbitraryAllowedGlobalSetsRoundTripIncludingZero() {
        for (bits in 0..15) {
            val days = listOf(7,3,1,0).filterIndexed { index,_ -> bits and (1 shl index) != 0 }.toSet()
            val p = mutablePreferencesOf(); val value = Settings(renewalOffsets = days)
            SettingsCodec.write(p,value); assertEquals(value,SettingsCodec.read(p))
        }
    }

    @Test fun absentAndExplicitlyEmptyAreDistinct() {
        assertEquals(setOf(3,1),SettingsCodec.read(emptyPreferences()).renewalOffsets)
        val p = mutablePreferencesOf(stringSetPreferencesKey("payment_offsets") to emptySet<String>())
        assertTrue(SettingsCodec.read(p).renewalOffsets.isEmpty())
    }

    @Test fun invalidCurrentContractFailsAndLegacyMembersAreSafelyFiltered() {
        assertThrows(IllegalArgumentException::class.java) { Settings(renewalOffsets = setOf(2)) }
        assertThrows(IllegalArgumentException::class.java) { Settings(version = 2) }
        val p = mutablePreferencesOf(stringPreferencesKey("theme") to "INVALID",
            stringSetPreferencesKey("payment_offsets") to setOf("bad","2","-1","3","0"))
        assertEquals(Theme.SYSTEM,SettingsCodec.read(p).theme)
        assertEquals(setOf(3,0),SettingsCodec.read(p).renewalOffsets)
        p[stringSetPreferencesKey("payment_offsets")] = setOf("bad","2")
        assertTrue(SettingsCodec.read(p).renewalOffsets.isEmpty())
    }

    @Test fun retiredKeysAreInertAndPreservedAcrossCurrentPreferenceWrites() {
        val time = stringPreferencesKey("reminder_time"); val currency = stringPreferencesKey("chart_currency")
        val horizon = intPreferencesKey("chart_months"); val cancellation = stringSetPreferencesKey("cancellation_offsets")
        val p = mutablePreferencesOf(time to "25:99",currency to "invalid",horizon to -1,cancellation to setOf("nonsense"))
        val read = SettingsCodec.read(p); assertEquals(LocalTime.of(9,0),read.reminderLocalTime)
        SettingsCodec.write(p,read.copy(theme = Theme.DARK,renewalOffsets = emptySet()))
        assertTrue(SettingsCodec.read(p).renewalOffsets.isEmpty())
        assertEquals("25:99",p[time]); assertEquals("invalid",p[currency]); assertEquals(-1,p[horizon])
        assertEquals(setOf("nonsense"),p[cancellation])
    }

}
