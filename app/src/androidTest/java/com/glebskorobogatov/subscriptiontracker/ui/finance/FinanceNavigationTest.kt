package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test

class FinanceNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<IsolatedMainActivity>()
    private fun forecastReady() {
        // Wait for the actual load, not for a virtualized/offscreen child of LazyColumn.
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("finance_screen").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Загружаем прогноз…").fetchSemanticsNodes().isEmpty()
        }
    }
    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag(tag))
    }
    @Test fun createdSubscriptionAppearsInForecastAndSelectionSurvivesRecreationAndSettingsBack() {
        compose.waitUntil(5000) { compose.onAllNodesWithText("Добавить первую подписку").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Добавить первую подписку").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("field_name").performTextInput("Учебная подписка")
        compose.onNodeWithTag("field_amount").performTextInput("499")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("tab_finance").performClick()
        forecastReady(); scrollTo("finance_total")
        compose.onNodeWithTag("finance_currency_RUB").assertDoesNotExist()
        compose.onNodeWithTag("finance_horizon_6").assertDoesNotExist()
        val month = YearMonth.now().plusMonths(1)
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_$month"))
        compose.onNodeWithTag("finance_month_$month").performClick().assertIsSelected()
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasText("Учебная подписка"))
        compose.onNodeWithText("Учебная подписка").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab_finance").assertIsSelected()
        forecastReady(); compose.onNodeWithTag("finance_horizon_6").assertDoesNotExist()
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_$month"))
        compose.onNodeWithTag("finance_month_$month").assertIsSelected()
        compose.onNodeWithContentDescription("Открыть настройки").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("tab_finance").assertIsSelected()
        forecastReady(); scrollTo("finance_month_$month")
        compose.onNodeWithTag("finance_month_$month").assertIsSelected()
    }
    @Test fun lastChartMonthSurvivesThemeTabReturnRecreationAndActualRotation() {
        compose.waitUntil(5000) { compose.onAllNodesWithText("Добавить первую подписку").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Добавить первую подписку").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("field_name").performTextInput("График — учебный сервис")
        compose.onNodeWithTag("field_amount").performTextInput("100")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("tab_finance").performClick(); forecastReady()
        compose.onNodeWithTag("finance_horizon_12").assertDoesNotExist()
        val last = YearMonth.now().plusMonths(11)
        fun chartSelected() {
            forecastReady(); scrollTo("finance_month_chart")
            // No explicit horizontal scroll: the selected column must be brought into view by the chart.
            compose.onNodeWithTag("finance_month_$last").assertIsSelected().assertIsDisplayed()
        }
        scrollTo("finance_month_chart")
        compose.onNodeWithTag("finance_month_$last").performScrollTo().performClick().assertIsSelected()
        compose.activityRule.scenario.recreate(); chartSelected()
        compose.onNodeWithContentDescription("Открыть настройки").performClick()
        compose.onNodeWithTag("settings_theme_DARK").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("settings_theme_DARK") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Назад").performClick(); chartSelected()
        compose.onNodeWithTag("tab_archive").performClick(); compose.onNodeWithTag("tab_finance").performClick(); chartSelected()
        fun orient(request: Int,expected: Int) {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = request }
            compose.waitUntil(10_000) {
                var matches = false
                compose.activityRule.scenario.onActivity { matches = it.resources.configuration.orientation == expected }
                matches
            }
        }
        try {
            orient(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            chartSelected(); compose.onNodeWithTag("tab_finance").assertIsSelected()
            val content = compose.onNodeWithTag("finance_screen").fetchSemanticsNode().boundsInRoot
            val island = compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(content.bottom <= island.top)
        } finally { orient(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,android.content.res.Configuration.ORIENTATION_PORTRAIT) }
        chartSelected()
    }

}
