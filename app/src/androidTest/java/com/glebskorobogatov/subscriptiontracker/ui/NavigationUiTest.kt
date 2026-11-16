package com.glebskorobogatov.subscriptiontracker.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import com.glebskorobogatov.subscriptiontracker.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<IsolatedMainActivity>()
    @Before fun ready() { compose.waitUntil(5000) { compose.onAllNodesWithTag("bottom_navigation").fetchSemanticsNodes().isNotEmpty() } }
    private fun tab(name: String) = compose.onNodeWithTag("tab_$name")
    private fun select(name: String) { tab(name).performClick(); tab(name).assertIsSelected() }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun openSettings() = compose.onNodeWithContentDescription(text(R.string.open_settings)).performClick()
    private fun financeEmpty(): SemanticsNodeInteraction {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("finance_empty").fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithTag("finance_empty")
    }
    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
    @Test fun launchShowsSubscriptions() {
        tab("subscriptions").assertIsSelected()
        compose.onNodeWithTag("subscriptions_screen").assertIsDisplayed()
    }
    @Test fun floatingIslandKeepsRootContentAboveNavigation() {
        for ((tabName, screenTag) in listOf("subscriptions" to "subscriptions_screen", "archive" to "archive_screen")) {
            select(tabName)
            compose.waitUntil(5000) { compose.onAllNodesWithTag(screenTag).fetchSemanticsNodes().isNotEmpty() }
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val island = compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag(screenTag).fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(island.left > root.left && island.right < root.right)
            org.junit.Assert.assertTrue(island.bottom < root.bottom)
            org.junit.Assert.assertTrue(content.bottom <= island.top)
        }
    }
    @Test fun subscriptionsHeaderAddOpensEditorAndBackKeepsIsland() {
        compose.onNodeWithContentDescription("Добавить подписку").assertIsDisplayed().performClick()
        compose.onNodeWithTag("subscription_editor").assertIsDisplayed()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Назад").performClick()
        tab("subscriptions").assertIsSelected()
        compose.onNodeWithContentDescription("Добавить подписку").assertIsDisplayed()
    }
    @Test fun financeTabShowsItsEmptyForecast() {
        select("finance")
        financeEmpty().assertIsDisplayed()
    }
    @Test fun archiveTabShowsItsEmptyState() {
        select("archive")
        compose.waitUntil(5000) { compose.onAllNodesWithText(text(R.string.archive_empty)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.archive_empty)).assertIsDisplayed()
    }
    @Test fun returnToSubscriptionsFromOtherTabs() {
        select("finance"); select("archive"); select("subscriptions")
        compose.onNodeWithTag("subscriptions_screen").assertIsDisplayed()
    }
    @Test fun settingsIsSeparateFromThreeTabs() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(3)
        openSettings()
        compose.onNodeWithText(text(R.string.settings_appearance)).assertIsDisplayed()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.navigate_back)).assertHasClickAction()
    }
    @Test fun toolbarBackReturnsToOriginatingTab() {
        for (name in listOf("subscriptions", "finance", "archive")) {
            select(name); openSettings()
            compose.onNodeWithContentDescription(text(R.string.navigate_back)).performClick()
            tab(name).assertIsSelected()
        }
    }
    @Test fun systemBackReturnsToOriginatingTab() {
        select("finance"); openSettings(); back(); tab("finance").assertIsSelected()
    }
    @Test fun repeatedTabSelectionDoesNotAccumulateBackStack() {
        repeat(4) { select("finance"); select("archive"); select("archive") }
        back(); tab("subscriptions").assertIsSelected()
    }
    @Test fun rapidSettingsTapDoesNotCreateDuplicateRoute() {
        select("archive")
        compose.onNodeWithContentDescription(text(R.string.open_settings)).performTouchInput { doubleClick() }
        compose.onNodeWithText(text(R.string.settings_appearance)).assertIsDisplayed()
        back(); tab("archive").assertIsSelected()
    }
    @Test fun selectedTabSurvivesActivityRecreation() {
        select("finance"); compose.activityRule.scenario.recreate()
        tab("finance").assertIsSelected()
        financeEmpty().assertIsDisplayed()
    }
    @Test fun settingsRouteAndOriginSurviveRecreation() {
        select("archive"); openSettings(); compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.settings_appearance)).assertIsDisplayed()
        back(); tab("archive").assertIsSelected()
    }
    @Test fun russianLabelsAndActionSemanticsAreVisible() {
        for ((name, label) in listOf("subscriptions" to "Подписки", "finance" to "Финансы", "archive" to "Архив")) {
            tab(name).assertTextContains(label).assertHasClickAction()
        }
        compose.onNodeWithContentDescription("Открыть настройки").assertHasClickAction()
        openSettings()
        compose.onAllNodesWithText("Настройки").assertCountEquals(1)
        compose.onNodeWithContentDescription("Назад").assertHasClickAction()
    }
    @Test fun orientationChangePreservesRouteAndControls() {
        select("finance")
        fun orient(request: Int, expected: Int) {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = request }
            compose.waitUntil(10_000) {
                var matches = false
                compose.activityRule.scenario.onActivity { matches = it.resources.configuration.orientation == expected }
                matches
            }
            tab("finance").assertIsSelected().assertIsDisplayed()
            compose.onNodeWithContentDescription(text(R.string.open_settings)).assertIsDisplayed()
            financeEmpty().performScrollTo().assertIsDisplayed()
        }
        try { orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE) }
        finally { orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT) }
    }
}
