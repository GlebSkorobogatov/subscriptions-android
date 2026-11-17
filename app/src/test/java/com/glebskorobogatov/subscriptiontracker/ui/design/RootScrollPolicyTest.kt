package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class RootScrollPolicyTest {
    private fun scrolling() = RootScrollPolicy().apply { viewport(true,false) }
    @Test fun twentyFourDpDownHidesAndTwentyFourUpShows() {
        val p = scrolling(); p.scroll(0f,-23f); assertFalse(p.hidden)
        p.scroll(0f,-1f); assertTrue(p.hidden)
        p.scroll(0f,23f); assertTrue(p.hidden)
        p.scroll(0f,1f); assertFalse(p.hidden)
    }
    @Test fun tinyIndependentGesturesNeverAccumulateToAnAccidentalHide() {
        val p = scrolling(); repeat(100) { p.scroll(0f,-5f); p.stop() }; assertFalse(p.hidden)
    }
    @Test fun reversingDirectionResetsHysteresisInsteadOfJittering() {
        val p = scrolling(); repeat(30) { p.scroll(0f,-10f); p.scroll(0f,10f) }
        assertFalse(p.hidden)
        p.scroll(0f,-24f); assertTrue(p.hidden)
        repeat(30) { p.scroll(0f,5f); p.scroll(0f,-5f) }; assertTrue(p.hidden)
    }
    @Test fun horizontalChartGesturesCannotHideThePanels() {
        val p = scrolling(); repeat(30) { p.scroll(-100f,-3f) }; assertFalse(p.hidden)
    }
    @Test fun topAlwaysShowsBothPanels() {
        val p = scrolling(); p.scroll(0f,-40f); assertTrue(p.hidden)
        p.viewport(true,true); assertFalse(p.hidden); p.scroll(0f,-50f); assertFalse(p.hidden)
    }
    @Test fun shortOrEmptyViewportNeverHides() {
        val p = RootScrollPolicy(); p.viewport(false,true); p.scroll(0f,-200f); assertFalse(p.hidden)
        p.viewport(true,false); p.scroll(0f,-40f); p.viewport(false,false); assertFalse(p.hidden)
    }
    @Test fun talkBackCanPinPanelsWithoutAnotherPreference() {
        val p = scrolling(); p.scroll(0f,-40f); p.viewport(true,false,true)
        p.scroll(0f,-200f); assertFalse(p.hidden)
    }
    @Test fun stoppingKeepsChosenVisibilityAndDropsOnlyPartialTravel() {
        val p = scrolling(); p.scroll(0f,-24f); p.stop(); assertTrue(p.hidden)
        p.scroll(0f,23f); p.stop(); p.scroll(0f,1f); assertTrue(p.hidden)
    }
    @Test fun unavailableViewportAndNonfiniteCoordinatesNeverInventScroll() {
        val p = RootScrollPolicy(); p.scroll(0f,-100f); assertFalse(p.hidden)
        p.viewport(true,false); p.scroll(Float.NaN,-50f); p.scroll(0f,Float.NaN); assertFalse(p.hidden)
    }
    @Test fun islandHasThreeRootsAndCompactAccessibleGeometry() {
        assertEquals(listOf(TrackerTab.Subscriptions,TrackerTab.Finance,TrackerTab.Settings),TrackerTab.entries)
        assertEquals(204.dp,TrackerDimensions.navigationMaxWidth)
        assertEquals(60.dp,TrackerDimensions.navigationHeight)
        assertTrue((204-12)/3 >= 48)
        assertEquals(0.85f,TrackerChrome.islandAlpha)
        assertTrue(TrackerChrome.headerAlpha in 0.90f..0.96f)
    }
    @Test fun foregroundContrastSurvivesExtremeContentBehindTranslucentPanels() {
        fun ratio(a: Color,b: Color): Float {
            val x=a.luminance(); val y=b.luminance();return (maxOf(x,y)+0.05f)/(minOf(x,y)+0.05f)
        }
        for(c in listOf(LightTrackerColors,DarkTrackerColors)) for(behind in listOf(Color.White,Color.Black)) {
            val island = c.surfaceRaised.copy(alpha = TrackerChrome.islandAlpha).compositeOver(behind)
            val header = c.background.copy(alpha = TrackerChrome.headerAlpha).compositeOver(behind)
            assertTrue(ratio(c.textSecondary,island) >= 3f) // icons only, no small tab labels
            assertTrue(ratio(c.textPrimary,c.selectedTabBg) >= 3f)
            assertTrue(ratio(c.textPrimary,header) >= 4.5f)
        }
    }
    @Test fun futureAnimationPreferenceAndSystemZeroBothGiveInstantTransitions() {
        assertEquals(0,TrackerMotion.durationMillis(false,1f))
        assertEquals(0,TrackerMotion.durationMillis(true,0f))
        assertEquals(220,TrackerMotion.durationMillis(true,1f))
    }
}
