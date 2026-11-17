package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real layout/gestures/pixels when run on Android; entirely synthetic, no dataset. */
class RootChromeUiTest {
    @get:Rule val compose = createComposeRule()
    private val rows = (1L..40L).map { SubscriptionsPreviewData.video.copy(id = UUID(0,it),name = "Учебная строка $it") }
    private fun state() = SubscriptionsPreviewData.content(rows,emptyList())
    @Test fun realVerticalGesturesHideAndReverseRestoresBothPanels() {
        compose.setContent { TrackerTheme { TrackerRootChrome(TrackerTab.Subscriptions,{},{},animationsEnabled = false) {
            SubscriptionsScreen(state(),{},{},{})
        } } }
        compose.onNodeWithTag("subscriptions_screen").performTouchInput {
            swipe(Offset(centerX,height*0.7f),Offset(centerX,height*0.25f),1000)
        }
        compose.onNodeWithTag("bottom_navigation").assertIsNotDisplayed()
        compose.onNodeWithTag("root_header").assertIsNotDisplayed()
        compose.onNodeWithTag("subscriptions_screen").performTouchInput {
            swipe(Offset(centerX,height*0.3f),Offset(centerX,height*0.5f),1000)
        }
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNodeWithTag("root_header").assertIsDisplayed()
    }
    @Test fun showHideDoesNotChangeListPositionOrReservedPadding() {
        var shown by mutableStateOf(true)
        lateinit var list: LazyListState
        compose.setContent { TrackerTheme { TrackerRootChrome(TrackerTab.Subscriptions,{},{},animationsEnabled = false,visibleOverride = shown) {
            list = rememberLazyListState()
            SubscriptionsScreen(state(),{},{},{},list)
        } } }
        compose.onNodeWithTag("subscriptions_screen").performScrollToIndex(12)
        var position = 0 to 0
        compose.runOnIdle { position = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset; shown = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(position,list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset); shown = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(position,list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset) }
    }
    @Test fun firstAndLastRowsStayAccessibleAboveOverlaysAndIconsRemainFiftyFourDp() {
        compose.setContent { TrackerTheme { TrackerRootChrome(TrackerTab.Subscriptions,{},{}) { SubscriptionsScreen(state(),{},{},{}) } } }
        val first = compose.onNodeWithTag("subscription_${rows.first().id}").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("root_header").fetchSemanticsNode().boundsInRoot
        assertTrue(first.top >= header.bottom)
        compose.onNodeWithTag("subscription_icon_${rows.first().id}",useUnmergedTree = true).assertWidthIsEqualTo(54.dp).assertHeightIsEqualTo(54.dp)
        compose.onNodeWithTag("subscriptions_screen").performScrollToIndex(rows.lastIndex)
        val last = compose.onNodeWithTag("subscription_${rows.last().id}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val island = compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().boundsInRoot
        assertTrue(last.bottom <= island.top)
    }
    @Test fun rowPlacesPriceRightOfNameAndProgressUnderEntireTextPart() {
        val row = rows.first()
        compose.setContent { TrackerTheme { Box(Modifier.requiredWidth(390.dp)) { SubscriptionsScreen(SubscriptionsPreviewData.content(listOf(row),emptyList()),{},{},{}) } } }
        val name = compose.onNodeWithTag("subscription_name_${row.id}",useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val price = compose.onNodeWithTag("subscription_price_${row.id}",useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag("subscription_text_${row.id}",useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val progress = compose.onNodeWithTag("subscription_progress_track_${row.id}",useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(price.left >= name.right)
        assertEquals(text.left,progress.left,1f); assertEquals(text.right,progress.right,1f)
        assertTrue(progress.top >= price.bottom)
    }
    @Test fun emptyAndShortScreensKeepPanelsVisible() {
        var state by mutableStateOf<SubscriptionsUiState>(SubscriptionsUiState.Empty())
        compose.setContent { TrackerTheme { TrackerRootChrome(TrackerTab.Subscriptions,{},{},animationsEnabled = false) { SubscriptionsScreen(state,{},{},{}) } } }
        compose.onNodeWithTag("subscriptions_screen").performTouchInput { swipeUp() }
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNodeWithTag("root_header").assertIsDisplayed()
        compose.runOnIdle { state = SubscriptionsPreviewData.content(listOf(rows.first()),emptyList()) }
        compose.onNodeWithTag("subscriptions_screen").performTouchInput { swipeUp() }
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
    }
    @Test fun horizontalAndProgrammaticScrollDoNotHideRootChrome() {
        compose.setContent { TrackerTheme { TrackerRootChrome(TrackerTab.Finance,{},{},animationsEnabled = false) {
            com.glebskorobogatov.subscriptiontracker.ui.finance.FinancePane(com.glebskorobogatov.subscriptiontracker.ui.preview.FinancePreviewData.chartState())
        } } }
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_chart"))
        compose.onNodeWithTag("finance_month_chart").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNodeWithTag("root_header").assertIsDisplayed()
    }
    @Test fun islandBackgroundAloneIsEightyFivePercentAndSelectionAndIconsAreOpaque() {
        compose.setContent { TrackerTheme(Theme.LIGHT) { Box(Modifier.fillMaxSize().background(Color.Black)) {
            TrackerBottomNavigation(TrackerTab.Subscriptions,{},windowInsets = WindowInsets(0,0,0,0))
        } } }
        val bitmap = compose.onNodeWithTag("bottom_navigation").captureToImage()
        val pixels = bitmap.toPixelMap()
        val scale = bitmap.width/204f
        val background = pixels[(102*scale).toInt(),(10*scale).toInt()]
        assertEquals(LightTrackerColors.surfaceRaised.copy(alpha = 0.85f).compositeOver(Color.Black).toArgb(),background.toArgb())
        assertEquals(LightTrackerColors.selectedTabBg.toArgb(),pixels[(38*scale).toInt(),(10*scale).toInt()].toArgb())
        assertTrue((18..58).any { x -> (17..43).any { y -> pixels[(x*scale).toInt(),(y*scale).toInt()].toArgb() == LightTrackerColors.textPrimary.toArgb() } })
    }
    @Test fun translucentWrapperDoesNotInterceptTouchesOutsideTheCompactIsland() {
        var clicks = 0
        compose.setContent { TrackerTheme { TrackerRootChrome(TrackerTab.Subscriptions,{},{}) {
            Box(Modifier.fillMaxSize()) {
                TrackerIconButton(Icons.Outlined.Close,"Фоновое действие",{ clicks++ },Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)).padding(bottom = 12.dp).testTag("background_action"))
            }
        } } }
        compose.onNodeWithTag("background_action").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1,clicks) }
    }
    @Test fun unplacedHiddenPanelsDoNotInterceptFormerIslandArea() {
        var clicks = 0
        compose.setContent { TrackerTheme { TrackerRootChrome(TrackerTab.Subscriptions,{},{},animationsEnabled = false,visibleOverride = false) {
            Box(Modifier.fillMaxSize()) {
                TrackerIconButton(Icons.Outlined.Close,"Фоновое действие",{ clicks++ },Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)).padding(bottom = 12.dp).testTag("background_action"))
            }
        } } }
        compose.onNodeWithTag("bottom_navigation").assertIsNotDisplayed()
        compose.onNodeWithTag("background_action").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1,clicks) }
    }
}
