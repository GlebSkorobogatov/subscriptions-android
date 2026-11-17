package com.glebskorobogatov.subscriptiontracker.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import com.glebskorobogatov.subscriptiontracker.ui.openSettingsOffsets
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real activity, DataStore and window on API 31. TalkBack itself is not claimed here. */
class SettingsRedesignUiTest {
    @get:Rule val compose = createAndroidComposeRule<IsolatedMainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Before fun ready() {
        shell("settings put global animator_duration_scale 1")
        shell("settings put system font_scale 1")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("bottom_navigation").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun restore() {
        shell("settings put global animator_duration_scale 1")
        shell("settings put system font_scale 1")
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { parcel ->
            java.io.FileInputStream(parcel.fileDescriptor).bufferedReader().use { it.readText() }
        }
    }
    private fun openSettings() {
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("settings_screen").assertExists()
    }
    private fun assertOneTheme(selected: String) {
        for(name in listOf("LIGHT","DARK","SYSTEM")) {
            val node = compose.onNodeWithTag("settings_theme_$name")
            if(name == selected) node.assertIsSelected() else node.assertIsNotSelected()
            node.assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton))
            node.assertHeightIsAtLeast(48.dp)
        }
        compose.onAllNodesWithTag("settings_theme_check",useUnmergedTree = true).assertCountEquals(1)
    }
    private fun revealSettings() {
        val screen = compose.onNodeWithTag("settings_screen")
        val offset = screen.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        if(offset > 1f) screen.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f,-offset) }
        compose.waitForIdle()
    }
    private fun choose(theme: String) {
        compose.onNodeWithTag("settings_theme_$theme").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings_theme_$theme").fetchSemanticsNodes().any {
            it.config.getOrElse(SemanticsProperties.Selected) { false } && !it.config.contains(SemanticsProperties.Disabled)
        } }
        assertOneTheme(theme)
    }
    @Test fun thumbnailsSelectExactlyOneThemeAndUpdateBarsWithoutRestart() {
        openSettings()
        assertOneTheme("SYSTEM")
        choose("LIGHT")
        compose.onNodeWithTag("theme_light").assertExists()
        compose.activityRule.scenario.onActivity {
            assertTrue(WindowCompat.getInsetsController(it.window,it.window.decorView).isAppearanceLightStatusBars)
            assertTrue(WindowCompat.getInsetsController(it.window,it.window.decorView).isAppearanceLightNavigationBars)
        }
        choose("DARK")
        compose.onNodeWithTag("theme_dark").assertExists()
        compose.activityRule.scenario.onActivity {
            assertFalse(WindowCompat.getInsetsController(it.window,it.window.decorView).isAppearanceLightStatusBars)
            assertFalse(WindowCompat.getInsetsController(it.window,it.window.decorView).isAppearanceLightNavigationBars)
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("settings_theme_DARK").assertIsSelected()
        compose.onNodeWithTag("theme_dark").assertExists()
        val systemDark = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        choose("SYSTEM")
        compose.onNodeWithTag(if(systemDark) "theme_dark" else "theme_light").assertExists()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }
    @Test fun animationSwitchPersistsAndDoesNotResetRouteOrScroll() {
        openSettings()
        compose.onNodeWithTag("settings_animations").assertIsOn()
        compose.onNodeWithText("Включены").assertIsDisplayed()
        val scroll = {
            compose.onNodeWithTag("settings_screen").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        }
        val before = scroll()
        compose.onNodeWithTag("settings_animations").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings_animations").fetchSemanticsNodes().any {
            it.config.getOrElse(SemanticsProperties.ToggleableState) { androidx.compose.ui.state.ToggleableState.Indeterminate } ==
                androidx.compose.ui.state.ToggleableState.Off && !it.config.contains(SemanticsProperties.Disabled)
        } }
        compose.onNodeWithText("Выключены").assertIsDisplayed()
        compose.onNodeWithText("Сохраняем настройку…").assertDoesNotExist()
        assertEquals(before,scroll(),1f)
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        compose.onNodeWithTag("settings_version").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("settings_screen").assertExists()
        compose.onNodeWithTag("settings_animations").performScrollTo().assertIsOff()
        compose.onNodeWithTag("settings_version").assertIsDisplayed()
        compose.onNodeWithTag("settings_animations").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings_animations").fetchSemanticsNodes().any {
            it.config.getOrElse(SemanticsProperties.ToggleableState) { androidx.compose.ui.state.ToggleableState.Indeterminate } ==
                androidx.compose.ui.state.ToggleableState.On && !it.config.contains(SemanticsProperties.Disabled)
        } }
    }
    @Test fun offsetsStayGlobalTimeStaysFixedAndArchiveReturns() {
        openSettings()
        compose.onNodeWithTag("settings_time").performScrollTo().assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        compose.onNodeWithTag("settings_time_value",useUnmergedTree = true).assertTextEquals("09:00")
        compose.onNodeWithTag("settings_time_dialog").assertDoesNotExist()
        compose.openSettingsOffsets()
        compose.onNodeWithTag("settings_payment_7").performScrollTo().performClick()
        compose.waitUntil(5_000) { offsetReady("settings_payment_7",true) }
        for(day in listOf(7,3,1)) {
            compose.onNodeWithTag("settings_payment_$day").performScrollTo().performClick()
            compose.waitUntil(5_000) { offsetReady("settings_payment_$day",false) }
        }
        compose.onNodeWithTag("settings_payment_0").assertIsOff()
        compose.onNode(hasText("Напоминания отключены.") and hasAnyAncestor(hasTestTag("settings_offsets_dialog"))).assertExists()
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithTag("settings_payment_offsets").performScrollTo().assert(hasText("Напоминания отключены."))
        compose.activityRule.scenario.recreate()
        compose.openSettingsOffsets()
        for(day in listOf(7,3,1,0)) compose.onNodeWithTag("settings_payment_$day").assertIsOff()
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithTag("settings_archive").performScrollTo().performClick()
        compose.onNodeWithTag("archive_screen").assertExists()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("settings_screen").assertExists()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }
    @Test fun aboutShowsInstalledVersionAndLocalDataWithoutBackup() {
        openSettings()
        compose.onNodeWithTag("settings_version").performScrollTo().assertTextEquals("Версия 0.1")
        compose.onNodeWithText("Подписки, настройки и изображения хранятся на этом устройстве.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Локальный трекер подписок. Без аккаунта, банковских подключений и синхронизации с сервером.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Данные").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("Резерв",substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Экспорт",substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("backup",substring = true,ignoreCase = true)).assertCountEquals(0)
    }
    @Test fun notificationSettingsOpensSystemScreenAndReflectsCurrentAccess() {
        openSettings()
        val manager = compose.activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val disabled = !manager.areNotificationsEnabled()
        val expected = if(disabled) "Уведомления отключены в системе" else "Уведомления разрешены"
        compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Настройки уведомлений").performScrollTo().performClick()
        val device = UiDevice.getInstance(instrumentation)
        assertNotNull(device.wait(Until.findObject(By.pkg("com.android.settings")),8_000))
        device.pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Android может задерживать напоминания. Время задаётся по текущему местному времени устройства.").performScrollTo().assertIsDisplayed()
    }
    @Test fun largeFontAndLandscapeKeepLabelsAndTheLastSection() {
        shell("settings put system font_scale 2")
        compose.activityRule.scenario.recreate()
        openSettings()
        compose.onNodeWithText("Светлая").assertIsDisplayed()
        compose.onNodeWithText("Тёмная").assertIsDisplayed()
        compose.onNodeWithText("Как в системе").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_animations").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("settings_version").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        compose.onNodeWithText("Светлая").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_version").performScrollTo().assertIsDisplayed()
        revealSettings()
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
    }
    private fun offsetReady(tag: String,enabled: Boolean) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any {
        it.config.getOrElse(SemanticsProperties.ToggleableState) { androidx.compose.ui.state.ToggleableState.Indeterminate } ==
            (if(enabled) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off) &&
            !it.config.contains(SemanticsProperties.Disabled)
    }
    @Test fun systemAnimatorScaleZeroIsReadAsInstantMotion() {
        shell("settings put global animator_duration_scale 0")
        val scale = Settings.Global.getFloat(compose.activity.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE)
        assertEquals(0f,scale,0.001f)
        assertEquals(0,com.glebskorobogatov.subscriptiontracker.ui.design.TrackerMotion.durationMillis(true,scale))
        assertEquals(0,com.glebskorobogatov.subscriptiontracker.ui.design.TrackerMotion.durationMillis(false,scale))
    }
}
