package com.glebskorobogatov.subscriptiontracker.ui.design
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import org.junit.Test
import org.junit.Assert.*
class TrackerThemeTest {
    @Test fun allSixPreferenceAndSystemCombinationsResolveCorrectly() {
        for(system in listOf(false,true)) {
            assertEquals(system,resolveDarkTheme(Theme.SYSTEM,system))
            assertFalse(resolveDarkTheme(Theme.LIGHT,system)); assertTrue(resolveDarkTheme(Theme.DARK,system))
        }
    }
}
