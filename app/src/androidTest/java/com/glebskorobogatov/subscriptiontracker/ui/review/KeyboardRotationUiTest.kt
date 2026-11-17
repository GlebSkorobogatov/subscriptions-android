package com.glebskorobogatov.subscriptiontracker.ui.review

import com.glebskorobogatov.subscriptiontracker.ui.openArchive
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import java.io.File
import org.junit.*
import org.junit.Assert.*

/** Real window/IME and Activity recreation; IsolatedMainActivity owns and cleans the test dataset. */
class KeyboardRotationUiTest {
    @get:Rule val compose = createAndroidComposeRule<IsolatedMainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Before fun ready() { compose.waitUntil(5000) { compose.onAllNodesWithTag("bottom_navigation").fetchSemanticsNodes().isNotEmpty() } }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);compose.waitForIdle() }
    private fun imeVisible(): Boolean {
        var visible = false
        compose.activityRule.scenario.onActivity { activity -> activity.window.decorView.rootWindowInsets?.let {
            visible = WindowInsetsCompat.toWindowInsetsCompat(it,activity.window.decorView).isVisible(WindowInsetsCompat.Type.ime())
        } }
        return visible
    }
    private fun showIme() {
        compose.activityRule.scenario.onActivity { WindowCompat.getInsetsController(it.window,it.window.decorView).show(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(5000) { imeVisible() }
    }
    private fun orient(request: Int,expected: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = request }
        compose.waitUntil(10_000) {
            var matches = false;compose.activityRule.scenario.onActivity { matches = it.resources.configuration.orientation == expected };matches
        }
    }
    @Test fun realKeyboardRotationCalendarAndUnsavedBackPreserveDraftAndRoot() {
        compose.onNodeWithContentDescription("Добавить подписку").performClick()
        compose.onNodeWithTag("field_name").performClick().performTextInput("Учебная проверка UI-06")
        showIme()
        compose.onNodeWithTag("field_name").assertIsFocused().assertTextContains("Учебная проверка UI-06")
        editorAboveIme()
        deviceScreenshot("editor-portrait-real-ime")
        back();compose.waitUntil(5000) { !imeVisible() }
        orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,Configuration.ORIENTATION_LANDSCAPE)
        try {
            compose.onNodeWithTag("field_name").performScrollTo().performClick()
            showIme();editorAboveIme()
            compose.onNodeWithTag("field_name").assertIsFocused().assertTextContains("Учебная проверка UI-06")
            deviceScreenshot("editor-landscape-real-ime")
            back();compose.waitUntil(5000) { !imeVisible() }
            compose.onNodeWithText("Сохранить").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
            deviceScreenshot("editor-landscape-draft")
            compose.onNodeWithTag("date_picker_date").performScrollTo().performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.editor_choose)).assertIsDisplayed().assertHasClickAction()
            deviceScreenshot("calendar-landscape")
            back()
            if(imeVisible()) { back();compose.waitUntil(5000) { !imeVisible() } }
            back()
            compose.onNodeWithText(compose.activity.getString(R.string.editor_unsaved)).assertExists()
            compose.onNodeWithText(compose.activity.getString(R.string.editor_continue)).performClick()
            compose.onNodeWithTag("field_name").assertTextContains("Учебная проверка UI-06")
            back();compose.onNodeWithText(compose.activity.getString(R.string.editor_discard)).performClick()
            compose.onNodeWithTag("tab_subscriptions").assertIsSelected()
            compose.onNodeWithText("Добавить подписку").performScrollTo().assertIsDisplayed()
        } finally { orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,Configuration.ORIENTATION_PORTRAIT) }
    }
    @Test fun landscapeGlobalOffsetsRecreationPreservesOriginTabAndFixedTime() {
        orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,Configuration.ORIENTATION_LANDSCAPE)
        try {
            compose.openArchive()
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithTag("settings_payment_offsets").performScrollTo().performClick()
            compose.onNodeWithTag("settings_payment_3").assertIsOn()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("settings_offsets_dialog").assertExists()
            compose.onNodeWithTag("settings_payment_3").assertIsOn()
            compose.onNodeWithText("Готово").assertIsDisplayed().performClick()
            compose.onNodeWithTag("settings_time_value",useUnmergedTree = true).performScrollTo().assertTextEquals("09:00")
            compose.onNodeWithTag("settings_time_dialog").assertDoesNotExist()
            compose.openArchive()
            compose.onNodeWithTag("archive_screen").assertExists()
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        } finally { orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,Configuration.ORIENTATION_PORTRAIT) }
    }

    private fun editorAboveIme() {
        var imeTop = 0
        compose.activityRule.scenario.onActivity { a ->
            val inset = WindowInsetsCompat.toWindowInsetsCompat(a.window.decorView.rootWindowInsets!!,a.window.decorView).getInsets(WindowInsetsCompat.Type.ime()).bottom
            imeTop = a.windowManager.currentWindowMetrics.bounds.height() - inset
        }
        val editor = compose.onNodeWithTag("subscription_editor").fetchSemanticsNode().boundsInWindow
        assertTrue("Editor scroll viewport must end above real IME: $editor vs $imeTop",editor.bottom <= imeTop + 1)
    }
    private fun deviceScreenshot(name: String) {
        val file = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),"ui06-device-$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        assertTrue("Device PNG must be stored",file.length() > 0)
    }
}
