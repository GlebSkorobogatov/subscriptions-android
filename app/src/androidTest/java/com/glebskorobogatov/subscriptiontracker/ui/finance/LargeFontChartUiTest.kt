package com.glebskorobogatov.subscriptiontracker.ui.finance

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.app.LargeFontReviewActivity
import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine
import com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import com.glebskorobogatov.subscriptiontracker.ui.preview.FinancePreviewData
import java.time.YearMonth
import org.junit.*
import org.junit.Assert.*

class LargeFontChartUiTest {
    @get:Rule val compose = createAndroidComposeRule<LargeFontReviewActivity>()
    @Test fun nativeFontTwoNarrowKeepsAllMonthsAndExactLargeMoneyAccessibleInBothThemes() =
        checkChart(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,Configuration.ORIENTATION_PORTRAIT)
    @Test fun nativeFontTwoLandscapeKeepsAllMonthsAndExactLargeMoneyAccessibleInBothThemes() =
        checkChart(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,Configuration.ORIENTATION_LANDSCAPE)
    private fun checkChart(request: Int,expected: Int) {
        fun orientation(value: Int,expectedValue: Int) {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = value }
            compose.waitUntil(10_000) {
                var matches = false
                compose.activityRule.scenario.onActivity { matches = it.resources.configuration.orientation == expectedValue }
                matches
            }
        }
        orientation(request,expected)

        val currency = CurrencyCode.of("RUB")
        val row = FinancePreviewData.rows.first().copy(priceMinor = Long.MAX_VALUE,intervalUnit = com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit.YEAR)
        val forecast = ForecastEngine().calendarMonths(listOf(row.toDomain()),FinancePreviewData.day,12,currency)
        var state by mutableStateOf(FinanceUiState(FinancePhase.Content,FinanceSnapshot(forecast,listOf(row)),currency,YearMonth.of(2027,1)))
        var theme by mutableStateOf(Theme.LIGHT)
        var density = 1f
        compose.setContent { density = LocalDensity.current.density
            TrackerTheme(theme) { Scaffold(bottomBar = { TrackerBottomNavigation(TrackerTab.Finance,{}) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    Box(Modifier.widthIn(max = 320.dp).fillMaxHeight()) { FinancePane(state,onMonth = { state = state.copy(month = it) }) }
                }
            } }
        }
        try {
                for(value in listOf(Theme.LIGHT,Theme.DARK)) {
                    compose.runOnIdle { theme = value }
                    compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_total"))
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithTag("finance_total").assertTextEquals("92233720368547758,07 RUB")
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue(layouts.isNotEmpty())
                    layouts.forEach { assertEquals(2f,it.layoutInput.density.fontScale,0.01f); assertFalse(it.hasVisualOverflow) }
                    for(month in forecast.months.keys) {
                        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_chart"))
                        val node = compose.onNodeWithTag("finance_month_$month").performScrollTo().assertIsDisplayed()
                        val bounds = node.fetchSemanticsNode().boundsInRoot
                        assertTrue("Visible touch width $month: $bounds",bounds.width >= 48*density-1)
                        assertTrue("Visible touch height $month: $bounds",bounds.height >= 48*density-1)
                        node.performClick().assertIsSelected()
                    }
                    val content = compose.onNodeWithTag("finance_screen").fetchSemanticsNode().boundsInRoot
                    val island = compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().boundsInRoot
                    assertTrue(content.bottom <= island.top)
                }
        } finally { orientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,Configuration.ORIENTATION_PORTRAIT) }
    }
}
