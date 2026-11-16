package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerContrastTest {
    private fun ratio(foreground: Color, background: Color): Float {
        val a = foreground.luminance(); val b = background.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
    private fun assertReadable(foreground: Color, background: Color) {
        val contrast = ratio(foreground, background)
        assertTrue("Normal text contrast $contrast must meet WCAG AA", contrast >= 4.5f)
    }
    private fun checkPalette(c: TrackerColors) {
        for (surface in listOf(c.background, c.surface, c.surfaceSubtle, c.surfaceRaised, c.selectedTabBg)) {
            assertReadable(c.textPrimary, surface)
            assertReadable(c.textSecondary, surface)
            assertReadable(c.error, surface)
        }
        assertReadable(c.primaryButtonText, c.primaryButtonBg)
    }
    @Test fun lightThemeTextAndButtonsMeetContrastTarget() = checkPalette(LightTrackerColors)
    @Test fun darkThemeTextAndButtonsMeetContrastTarget() = checkPalette(DarkTrackerColors)
    @Test fun selectedTabOutlineAndMeaningfulProgressColorsMeetNonTextContrast() {
        for(c in listOf(LightTrackerColors, DarkTrackerColors)) {
            assertTrue("Selected tab outline", ratio(c.textSecondary, c.selectedTabBg) >= 3f)
            for(color in listOf(c.statusCalm, c.statusSoon, c.statusUrgent)) {
                assertTrue("Progress on surface", ratio(color, c.surface) >= 3f)
                assertTrue("Progress on subtle surface", ratio(color, c.surfaceSubtle) >= 3f)
            }
        }
    }
}
