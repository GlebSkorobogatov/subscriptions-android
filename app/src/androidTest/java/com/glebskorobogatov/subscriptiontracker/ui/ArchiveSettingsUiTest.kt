package com.glebskorobogatov.subscriptiontracker.ui

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import java.time.LocalDate
import org.junit.*
import org.junit.Assert.*

class ArchiveSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<IsolatedMainActivity>()
    @Before fun ready() { compose.waitUntil(5000) { compose.onAllNodesWithTag("bottom_navigation").fetchSemanticsNodes().isNotEmpty() } }
    private fun settings() {
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithText("Оформление").assertExists()
    }
    private fun checked(tag: String,expected: Boolean) {
        compose.openSettingsOffsets()
        compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any {
            it.config.getOrElse(SemanticsProperties.ToggleableState) { androidx.compose.ui.state.ToggleableState.Indeterminate } ==
                (if(expected) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off) && !it.config.contains(SemanticsProperties.Disabled)
        } }
    }
    private fun theme(value: String,dark: Boolean) {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("settings_theme_$value").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) } }
        compose.onNodeWithTag("settings_theme_$value").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("settings_theme_$value").fetchSemanticsNodes().any {
            it.config.getOrElse(SemanticsProperties.Selected) { false } && !it.config.contains(SemanticsProperties.Disabled)
        } }
        compose.onNodeWithTag(if(dark) "theme_dark" else "theme_light").assertExists()
    }
    private fun createAndOpen(label: String = "Личный тест") {
        compose.waitUntil(5000) { compose.onAllNodesWithText("Добавить подписку").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Добавить подписку").performClick()
        compose.onNodeWithTag("field_name").performTextInput("Архив D05")
        compose.onNodeWithTag("field_amount").performTextInput("499")
        compose.onNodeWithText("Дополнительно").performScrollTo().performClick()
        compose.onNodeWithTag("field_label").performScrollTo().performTextInput(label)
        compose.onNodeWithText("Сохранить").performScrollToIfScrollable().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("Архив D05"))
        compose.onNodeWithText("Архив D05").performClick()
        compose.openDetailsManagement()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Архивировать").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun confirm() {
        compose.onNodeWithText("Подтвердить").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Подтвердить").fetchSemanticsNodes().isEmpty() }
    }
    private fun archiveAndReturn() {
        compose.onNodeWithText("Архивировать").performScrollTo().performClick(); confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Возобновить подписку").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Назад").performClick()
    }
    @Test fun emptyArchiveAndSettingsReturnToOriginWithoutFourthTab() {
        compose.openArchive()
        compose.waitUntil(5000) { compose.onAllNodesWithText("В архиве пока пусто").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("archive_screen").assertExists()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("settings_screen").assertExists()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }
    @Test fun archiveReactivationPreservesUuidAndUpdatesActiveForecast() {
        createAndOpen()
        archiveAndReturn(); compose.openArchive()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Личный тест").fetchSemanticsNodes().isNotEmpty() }
        val archiveMatcher = SemanticsMatcher("archive UUID card") { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.matches(Regex("archive_[0-9a-f-]{36}")) }
        val tag = compose.onNode(archiveMatcher).fetchSemanticsNode().config[SemanticsProperties.TestTag]
        val id = tag.removePrefix("archive_")
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag("details_$id").assertExists()
        compose.openDetailsManagement()
        compose.onNodeWithText("Возобновить подписку").performScrollTo().performClick()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Возобновить подписку").performScrollTo().performClick()
        compose.onNodeWithTag("details_field_date").performScrollTo().performTextReplacement(LocalDate.now().plusDays(2).toString())
        compose.onNodeWithTag("details_field_amount").performScrollTo().performTextReplacement("599")
        compose.onNodeWithTag("details_field_count").performScrollTo().performTextReplacement("3")
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Возобновить подписку").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("599,00 RUB").assertExists()
        compose.onAllNodes(hasText("Оплата ",substring = true)).assertCountEquals(0)
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("В архиве пока пусто").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Назад").performClick() // Archive -> Settings.
        compose.onNodeWithTag("tab_subscriptions").performClick()
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasTestTag("subscription_$id"))
        compose.onNodeWithTag("subscription_$id").assertExists()
        compose.onNodeWithText("599 ₽").assertExists()
        compose.onNodeWithTag("tab_finance").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("finance_30_total").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_30_total"))
        compose.onNodeWithTag("finance_30_total").assertTextEquals("599,00 RUB")
    }
    @Test fun duplicateArchiveNamesKeepAccountLabelsAndIndependentCards() {
        createAndOpen("Личный тест"); archiveAndReturn()
        createAndOpen("Семейный тест"); archiveAndReturn()
        compose.openArchive()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Семейный тест").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Архив D05").assertCountEquals(2)
        compose.onNodeWithText("Личный тест").assertExists(); compose.onNodeWithText("Семейный тест").assertExists()
    }
    @Test fun themeChangesImmediatelyAndSurvivesRecreationIncludingSystemChoice() {
        compose.onNodeWithTag("tab_finance").performClick(); settings()
        theme("LIGHT",false); theme("DARK",true)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("theme_dark").assertExists(); compose.onNodeWithTag("settings_theme_DARK").assertIsSelected()
        val systemDark = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        theme("SYSTEM",systemDark)
        compose.onNodeWithTag("tab_finance").performClick(); compose.onNodeWithTag("tab_finance").assertIsSelected()
    }
    @Test fun fixedNineSurvivesRecreationWithoutTimeDialog() {
        settings()
        compose.onNodeWithTag("settings_time").performScrollTo().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithTag("settings_time_value",useUnmergedTree = true).assertTextEquals("09:00")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("settings_time").performScrollTo().assertExists()
        compose.onNodeWithTag("settings_time_value",useUnmergedTree = true).assertTextEquals("09:00")
        compose.onNodeWithTag("settings_time_dialog").assertDoesNotExist()
    }

    @Test fun globalOffsetsSupportMultipleSelectionEmptySetAndRecreation() {
        settings(); checked("settings_payment_3",true)
        compose.onNodeWithTag("settings_payment_7").performScrollTo().performClick(); checked("settings_payment_7",true)
        for(day in listOf(7,3,1)) { compose.onNodeWithTag("settings_payment_$day").performScrollTo().performClick(); checked("settings_payment_$day",false) }
        compose.onNodeWithText("Готово").performClick()
        compose.activityRule.scenario.recreate(); compose.openSettingsOffsets()
        for(day in listOf(7,3,1,0)) checked("settings_payment_$day",false)
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithTag("settings_cancellation_offsets").assertDoesNotExist()
    }


    @Test fun reminderSettingsExplainBestEffortAndDoNotPublishOnOpening() {
        settings(); compose.onNodeWithText("Android может задерживать напоминания. Время задаётся по текущему местному времени устройства.").performScrollTo().assertIsDisplayed()
        @Suppress("DEPRECATION")
        val permissions = compose.activity.packageManager.getPackageInfo(compose.activity.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue("android.permission.POST_NOTIFICATIONS" in permissions)
        assertTrue((compose.activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications.isEmpty())
    }
}
