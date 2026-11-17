package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import org.junit.Rule
import org.junit.Test

class RootNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<IsolatedMainActivity>()
    private fun ready() { compose.waitUntil(5000) { compose.onAllNodesWithText("Подписок пока нет").fetchSemanticsNodes().isNotEmpty() } }
    @Test fun exactlyThreeIconTabsExposeRussianNamesAndNoArchiveTab() {
        ready()
        compose.onNodeWithTag("tab_subscriptions").assertContentDescriptionEquals("Подписки").assertIsSelected()
        compose.onNodeWithTag("tab_finance").assertContentDescriptionEquals("Статистика")
        compose.onNodeWithTag("tab_settings").assertContentDescriptionEquals("Настройки")
        compose.onNodeWithTag("tab_archive").assertDoesNotExist()
        compose.onNodeWithContentDescription("Открыть настройки").assertDoesNotExist()
        compose.onNodeWithContentDescription("Добавить подписку").assertExists()
    }
    @Test fun archiveIsNestedInSettingsAndBackReturnsToSettingsAfterRecreation() {
        ready(); compose.openArchive()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("archive_screen").assertExists()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        compose.onNodeWithTag("settings_screen").assertExists()
    }
    @Test fun repeatedRootTapDoesNotAddAnotherDestinationAndSystemBackReturnsHome() {
        ready(); compose.onNodeWithTag("tab_settings").performClick()
        repeat(4) { compose.onNodeWithTag("tab_settings").performClick().assertIsSelected() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("tab_subscriptions").assertIsSelected()
    }
    @Test fun createEditorIsNestedAndBackPreservesHomeWithoutBottomIsland() {
        ready(); compose.onNodeWithContentDescription("Добавить подписку").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("tab_subscriptions").assertIsSelected()
        compose.onNodeWithTag("forecast_summary").assertDoesNotExist()
    }
}
