package com.glebskorobogatov.subscriptiontracker.ui

import android.content.ContentValues
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import com.glebskorobogatov.subscriptiontracker.ui.design.LocalTrackerAnimations
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerBottomSheet
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.editor.EditorForm
import com.glebskorobogatov.subscriptiontracker.ui.preview.DetailsEditorPreviewData
import com.glebskorobogatov.subscriptiontracker.ui.preview.EditorDemo
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real editor path on API 31. TalkBack itself is not claimed from Compose semantics. */
class R5aEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<IsolatedMainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Before fun ready() {
        shell("settings put system font_scale 1")
        shell("settings put global animator_duration_scale 1")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("bottom_navigation").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun restore() {
        shell("settings put system font_scale 1")
        shell("settings put global animator_duration_scale 1")
        runCatching { compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } }
    }

    @Test fun createAndEditExistingSubscription() {
        open()
        shot("create-empty")
        compose.onNodeWithTag("editor_icon").assertIsDisplayed()
        compose.onNodeWithText("Изменить значок").assertIsDisplayed()
        compose.onNodeWithTag("field_name").performTextInput("Учебный R5a")
        compose.onNodeWithTag("field_label").performTextInput("Личный")
        compose.onNodeWithTag("field_amount").performTextInput("299,50")
        compose.onNodeWithTag("frequency_month").assertIsSelected()
        shot("create-filled")
        save()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("Учебный R5a"))
        compose.onNodeWithText("Учебный R5a").performClick()
        compose.onNodeWithText("299,50 ₽").assertExists()
        compose.onNodeWithText("Редактировать").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Редактирование подписки").fetchSemanticsNodes().isNotEmpty() }
        shot("edit-existing")
        compose.onNodeWithTag("field_name").performTextReplacement("Учебный R5a изменён")
        compose.onNodeWithTag("field_amount").assertTextContains("299,50")
        save()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Учебный R5a изменён").assertExists()
        compose.onAllNodesWithText("Учебный R5a").assertCountEquals(0)
    }

    @Test fun iconIsCenteredAndCatalogCloseKeepsOtherFields() {
        open()
        val editor = compose.onNodeWithTag("subscription_editor").fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag("editor_icon").fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithTag("field_name").fetchSemanticsNode().boundsInRoot
        assertEquals(editor.center.x, icon.center.x, 2f)
        assertTrue("Icon sits above the name field", icon.bottom <= name.top)
        compose.onNodeWithTag("field_name").performTextInput("Не менять")
        compose.onNodeWithTag("editor_choose_catalog").performClick()
        compose.onNodeWithTag("catalog_sheet").assertIsDisplayed()
        compose.onNodeWithTag("catalog_pick_image").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("catalog_generic-other").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Закрыть каталог").assertIsDisplayed().performClick()
        compose.onNodeWithTag("catalog_sheet").assertDoesNotExist()
        compose.onNodeWithTag("field_name").assertTextContains("Не менять")
        compose.onNodeWithText("Убрать оформление").assertDoesNotExist()
        compose.onNodeWithTag("editor_choose_catalog").performClick()
        compose.onNodeWithTag("catalog_generic-video").performScrollTo().performClick()
        compose.onNodeWithTag("field_name").assertTextContains("Не менять")
        compose.onNodeWithText("Убрать оформление").assertIsDisplayed()
        compose.onNodeWithTag("editor_choose_catalog").performClick()
        compose.onNodeWithTag("catalog_generic-video").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("catalog_selected_mark", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("catalog_generic-other").performScrollTo().assertIsNotSelected()
    }

    @Test fun presetsCustomCalendarAndValidation() {
        open()
        compose.onNodeWithTag("frequency_month").assertIsSelected()
        compose.onNodeWithTag("field_count").assertDoesNotExist()
        compose.onNodeWithTag("frequency_quarter").performClick().assertIsSelected()
        compose.onNodeWithTag("field_count").assertDoesNotExist()
        compose.onNodeWithTag("frequency_custom").performClick().assertIsSelected()
        compose.onNodeWithTag("field_count").performScrollTo().assertTextContains("1")
        compose.onNodeWithTag("frequency_unit_DAY").assertIsSelected()
        compose.onNodeWithTag("field_count").performTextReplacement("6")
        compose.onNodeWithTag("frequency_unit_MONTH").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("frequency_custom").assertIsSelected()
        compose.onNodeWithTag("frequency_week").performClick().assertIsSelected()
        compose.onNodeWithTag("field_count").assertDoesNotExist()
        compose.onNodeWithTag("frequency_custom").performClick()
        compose.onNodeWithTag("field_count").assertTextContains("1")
        val before = compose.onNodeWithTag("field_date").fetchSemanticsNode()
        val original = before.config[SemanticsProperties.EditableText].text
        compose.onNodeWithTag("date_picker_date").performScrollTo().performClick()
        compose.onNodeWithTag("editor_date_sheet").assertIsDisplayed()
        compose.onNodeWithTag("editor_date_confirm").assertIsDisplayed().assertHasClickAction()
        shot("calendar")
        back()
        compose.onNodeWithTag("editor_date_sheet").assertDoesNotExist()
        compose.onNodeWithTag("field_date").assertTextContains(original)
        compose.onNodeWithTag("date_picker_date").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor_date_sheet").fetchSemanticsNodes().isNotEmpty() }
        val next = LocalDate.now().plusMonths(1).withDayOfMonth(1)
        val russian = Locale.forLanguageTag("ru")
        val monthNames = listOf(TextStyle.FULL, TextStyle.FULL_STANDALONE).map { next.month.getDisplayName(it, russian) }
        compose.onNode(hasContentDescription("Перейти к следующему месяцу", substring = true)).assertExists().performClick()
        compose.onAllNodes(hasContentDescription("Change to next month", substring = true)).assertCountEquals(0)
        val dayMatcher = calendarDay(next.dayOfMonth, monthNames)
        val days = compose.onAllNodes(dayMatcher)
        if (days.fetchSemanticsNodes().isEmpty()) {
            compose.onAllNodes(dayMatcher, useUnmergedTree = true).onFirst().performClick()
        } else {
            days.onFirst().performClick()
        }
        compose.onNodeWithTag("editor_date_confirm").performClick()
        compose.onNodeWithTag("editor_date_sheet").assertDoesNotExist()
        compose.onNodeWithTag("field_date").assertTextContains(next.toString(), substring = true)
        save()
        compose.onNodeWithTag("field_name").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("subscription_editor").assertExists()
        compose.onNodeWithTag("field_name").performTextInput("Проверка")
        compose.onNodeWithTag("field_amount").performTextInput("нет")
        save()
        compose.onNodeWithTag("field_amount").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        shot("validation")
        compose.onNodeWithTag("field_amount").performTextReplacement("299,50")
        compose.onNodeWithTag("editor_extra").performScrollTo().performClick()
        compose.onNodeWithTag("field_anchorDay").performScrollTo().performTextInput("32")
        compose.onNodeWithTag("editor_extra").performScrollTo().performClick()
        compose.onNodeWithTag("field_anchorDay").assertDoesNotExist()
        save()
        compose.onNodeWithTag("field_anchorDay").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test fun customModeKeepsPresetValuesUntilAnExplicitCapsuleAndSurvivesRotation() {
        open()
        compose.onNodeWithTag("frequency_custom").performClick().assertIsSelected()
        compose.onNodeWithTag("field_count").performScrollTo().assertTextContains("1")
        compose.onNodeWithTag("frequency_unit_DAY").assertIsSelected()
        compose.onNodeWithTag("frequency_unit_MONTH").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("frequency_custom").assertIsSelected()
        compose.onNodeWithTag("frequency_month").assertIsNotSelected()
        compose.onNodeWithTag("field_count").assertIsDisplayed().performTextReplacement("6")
        compose.onNodeWithTag("frequency_custom").assertIsSelected()
        compose.onNodeWithTag("frequency_custom").performClick()
        compose.onNodeWithTag("field_count").assertTextContains("6")
        compose.onNodeWithTag("frequency_unit_MONTH").assertIsSelected()
        compose.onNodeWithTag("field_count").performTextReplacement("1")
        compose.onNodeWithTag("frequency_unit_WEEK").performScrollTo().performClick()
        compose.onNodeWithTag("frequency_custom").assertIsSelected()
        compose.onNodeWithTag("frequency_week").assertIsNotSelected()
        compose.onNodeWithTag("field_count").performTextReplacement("3")
        compose.onNodeWithTag("frequency_unit_MONTH").performClick()
        compose.onNodeWithTag("frequency_custom").assertIsSelected()
        compose.onNodeWithTag("frequency_quarter").assertIsNotSelected()
        compose.onNodeWithTag("field_count").performTextReplacement("1")
        compose.onNodeWithTag("frequency_unit_YEAR").performScrollTo().performClick()
        compose.onNodeWithTag("frequency_custom").assertIsSelected()
        compose.onNodeWithTag("frequency_year").assertIsNotSelected()
        orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("field_count").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("frequency_custom").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("frequency_unit_YEAR").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("field_count").assertTextContains("1")
        orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("field_count").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("frequency_custom").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("field_count").assertTextContains("1")
        compose.onNodeWithTag("frequency_month").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("field_count").assertDoesNotExist()
    }

    @Test fun calendarStaysRussianWhenAnimatorScaleIsZero() {
        shell("settings put global animator_duration_scale 0")
        try {
            open()
            compose.onNodeWithTag("date_picker_date").performScrollTo().performClick()
            compose.onNodeWithTag("editor_date_sheet").assertIsDisplayed()
            compose.onNode(hasContentDescription("Перейти к следующему месяцу", substring = true)).assertExists()
            compose.onAllNodes(hasContentDescription("Change to next month", substring = true)).assertCountEquals(0)
        } finally {
            shell("settings put global animator_duration_scale 1")
        }
    }

    @Test fun sheetsStayUsableWhenMotionIsZeroAndWhenItIsEnabled() {
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("settings_animations").performScrollTo().performClick()
        compose.onNodeWithTag("settings_animations").assertIsOff()
        compose.onNodeWithTag("tab_subscriptions").performClick()
        exerciseSheets()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("settings_animations").performScrollTo().performClick()
        compose.onNodeWithTag("settings_animations").assertIsOn()
        shell("settings put global animator_duration_scale 0")
        try {
            compose.onNodeWithTag("tab_subscriptions").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Добавить подписку").fetchSemanticsNodes().isNotEmpty() }
            exerciseSheets()
            shell("settings put system font_scale 2")
            compose.waitUntil(8_000) {
                var ready = false
                runCatching {
                    compose.activityRule.scenario.onActivity { activity ->
                        ready = activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                            kotlin.math.abs(activity.resources.configuration.fontScale - 2f) < 0.05f
                    }
                }
                ready
            }
            orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            compose.waitUntil(8_000) { compose.onAllNodesWithText("Добавить подписку").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Добавить подписку").performScrollTo()
            open()
            compose.onNodeWithTag("editor_choose_catalog").performScrollTo().performClick()
            val pick = compose.onNodeWithTag("catalog_pick_image").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes().first().boundsInRoot
            assertTrue("Catalog action stays inside the landscape window: $pick vs $root", pick.top >= root.top && pick.bottom <= root.bottom + 1f)
            back()
            compose.onNodeWithTag("catalog_sheet").assertDoesNotExist()
            compose.onNodeWithTag("date_picker_date").performScrollTo().performClick()
            val confirm = compose.onNodeWithTag("editor_date_confirm").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Date confirmation stays inside the landscape window: $confirm vs $root", confirm.top >= root.top && confirm.bottom <= root.bottom + 1f)
        } finally {
            shell("settings put global animator_duration_scale 1")
            shell("settings put system font_scale 1")
            orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        }
    }

    private fun exerciseSheets() {
        open()
        compose.onNodeWithTag("field_name").performTextInput("Черновик листа")
        compose.onNodeWithTag("date_picker_date").performScrollTo().performClick()
        compose.onNodeWithTag("editor_date_sheet").assertIsDisplayed()
        compose.onNode(hasContentDescription("Перейти к следующему месяцу", substring = true)).assertExists()
        compose.onNodeWithTag("editor_date_confirm").assertIsDisplayed()
        val covered = runCatching { compose.onNodeWithTag("field_name").performClick() }
        compose.onNodeWithTag("field_name").assertTextContains("Черновик листа")
        if(covered.isSuccess) compose.onNodeWithTag("subscription_editor").assertExists()
        if(compose.onAllNodesWithTag("editor_date_sheet").fetchSemanticsNodes().isNotEmpty()) back()
        compose.onNodeWithTag("editor_date_sheet").assertDoesNotExist()
        compose.onNodeWithTag("field_name").assertTextContains("Черновик листа")
        compose.onNodeWithTag("editor_choose_catalog").performScrollTo().performClick()
        compose.onNodeWithTag("catalog_sheet").assertIsDisplayed()
        compose.onNodeWithTag("catalog_generic-other").performScrollTo().performClick()
        compose.onNodeWithTag("catalog_sheet").assertDoesNotExist()
        compose.onNodeWithTag("field_name").assertTextContains("Черновик листа")
        compose.onNodeWithTag("editor_choose_catalog").performScrollTo().performClick()
        compose.onNodeWithTag("catalog_sheet").assertIsDisplayed()
        back()
        compose.onNodeWithTag("catalog_sheet").assertDoesNotExist()
        compose.onNodeWithText("Есть несохранённые изменения").assertDoesNotExist()
        compose.onNodeWithTag("field_name").assertTextContains("Черновик листа")
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Выйти без сохранения").performClick()
        compose.onNodeWithTag("bottom_navigation").assertExists()
    }

    @Test fun pinnedSaveStaysAvailableWithImeScrollFontAndLandscape() {
        open()
        compose.onNodeWithTag("field_name").performClick().performTextInput("Клавиатура")
        showIme()
        compose.onNodeWithTag("editor_save").assertIsDisplayed()
        editorAboveIme()
        shot("ime")
        back()
        compose.waitUntil(5_000) { !imeVisible() }
        compose.onNodeWithTag("editor_extra").performScrollTo().performClick()
        compose.onNodeWithTag("field_start").performScrollTo()
        val field = compose.onNodeWithTag("field_start").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("editor_save").fetchSemanticsNode().boundsInRoot
        assertTrue("Last field stays above the pinned save button", field.bottom <= save.top + 1f)
        compose.onNodeWithTag("editor_save").assertIsDisplayed()
        orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        try {
            compose.onNodeWithTag("editor_save").assertIsDisplayed()
            compose.onNodeWithTag("frequency_year").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
            shot("landscape")
        } finally { orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT) }
        shell("settings put system font_scale 2")
        // The system recreates the activity for the new font scale. Wait until that instance is
        // resumed, then recreate once more so restoration is not racing the configuration change.
        compose.waitUntil(8_000) {
            var ready = false
            runCatching {
                compose.activityRule.scenario.onActivity { activity ->
                    ready = activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                        kotlin.math.abs(activity.resources.configuration.fontScale - 2f) < 0.05f
                }
            }
            ready
        }
        compose.activityRule.scenario.recreate()
        try {
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("field_name").assertTextContains("Клавиатура")
            compose.onNodeWithTag("frequency_custom").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("editor_save").assertIsDisplayed()
            shot("font-2")
        } finally { shell("settings put system font_scale 1") }
    }

    @Test fun backClosesTheSheetBeforeTheDraftAndRecreationKeepsIt() {
        open()
        compose.onNodeWithTag("field_name").performTextInput("Черновик R5a")
        compose.onNodeWithTag("editor_choose_catalog").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("catalog_sheet").assertDoesNotExist()
        compose.onNodeWithText("Есть несохранённые изменения").assertDoesNotExist()
        compose.onNodeWithTag("date_picker_date").performScrollTo().performClick()
        back()
        compose.onNodeWithTag("editor_date_sheet").assertDoesNotExist()
        compose.onNodeWithText("Есть несохранённые изменения").assertDoesNotExist()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Есть несохранённые изменения").assertIsDisplayed()
        shot("unsaved")
        compose.onNodeWithText("Продолжить редактирование").performClick()
        compose.onNodeWithTag("field_name").assertTextContains("Черновик R5a")
        compose.onNodeWithTag("editor_extra").performScrollTo().performClick()
        compose.onNodeWithTag("field_anchorDay").performScrollTo().performTextInput("31")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("field_name").assertTextContains("Черновик R5a")
        compose.onNodeWithTag("field_anchorDay").performScrollTo().assertTextContains("31")
        compose.onNodeWithTag("editor_save").performTouchInput { doubleClick() }
        compose.onNodeWithTag("subscription_editor").assertExists()
        compose.onNodeWithTag("field_amount").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test fun cancelledEditDoesNotReactivateTheSeries() {
        open()
        compose.onNodeWithTag("field_name").performTextInput("Отменённая R5a")
        compose.onNodeWithTag("field_amount").performTextInput("100")
        save()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Отменённая R5a").performClick()
        compose.openDetailsManagement()
        compose.onNodeWithText("Отметить отмену").performClick()
        compose.onNodeWithText("Подтвердить").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Отменена").fetchSemanticsNodes().isNotEmpty() }
        compose.openDetailsManagement()
        compose.onNodeWithTag("details_action_edit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("frequency_month").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("frequency_month").assertIsNotEnabled()
        compose.onNodeWithTag("frequency_custom").assertIsNotEnabled()
        compose.onNodeWithTag("field_date").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_name").assertIsEnabled().performTextReplacement("Отменённая R5a имя")
        compose.onNodeWithTag("field_amount").assertIsEnabled()
        save()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Отменена").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Отменённая R5a имя").assertExists()
        compose.onNodeWithText("Отменена").assertIsDisplayed()
        compose.onNodeWithText("Активна").assertDoesNotExist()
    }

    @Test fun repeatedSaveCreatesOneCard() {
        open()
        compose.onNodeWithTag("field_name").performTextInput("Одна R5a")
        compose.onNodeWithTag("field_amount").performTextInput("100")
        compose.onNodeWithTag("editor_save").performTouchInput { doubleClick() }
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText("Одна R5a").assertCountEquals(1)
    }

    @Test fun photoPickerSelectsSyntheticImageWhenAvailable() {
        val resolver = compose.activity.contentResolver
        val uri = try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "r5a-synthetic.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            checkNotNull(inserted)
            resolver.openOutputStream(inserted)!!.use { out ->
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.rgb(40, 90, 160))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(inserted, values, null, null)
            inserted
        } catch (error: Exception) {
            assumeTrue("MediaStore insert is unavailable: ${error.javaClass.simpleName}", false)
            throw error
        }
        try {
            open()
            compose.onNodeWithTag("editor_choose_catalog").performClick()
            val bounds = compose.onNodeWithTag("catalog_pick_image").fetchSemanticsNode().boundsInWindow
            val own = device.currentPackageName
            device.click(bounds.center.x.roundToInt(), bounds.center.y.roundToInt())
            val opened = waitForPackageChange(own)
            assumeTrue("System Photo Picker did not open", opened)
            shot("picker-open")
            val hierarchy = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "r5a-picker-hierarchy.xml")
            device.dumpWindowHierarchy(hierarchy)
            val thumb = device.wait(Until.findObject(By.res("com.google.android.documentsui:id/icon_thumb")), 4_000)
                ?: device.wait(Until.findObject(By.descContains("r5a-synthetic.png,")), 2_000)
            val listed = checkNotNull(thumb) { "Synthetic image was not listed by the system picker" }
            fun tapAwayFromPreview(target: androidx.test.uiautomator.UiObject2) {
                val area = target.visibleBounds
                device.click(area.left + 36, area.bottom - 36)
            }
            tapAwayFromPreview(listed)
            val started = android.os.SystemClock.uptimeMillis()
            var landed = device.currentPackageName
            while (android.os.SystemClock.uptimeMillis() - started < 4_000 && landed.contains("documentsui")) {
                android.os.SystemClock.sleep(200)
                landed = device.currentPackageName
            }
            if (landed.contains("photos") || landed.contains("gallery")) {
                device.pressBack()
                val row = device.wait(Until.findObject(By.descContains("r5a-synthetic.png,")), 3_000)
                if (row != null) tapAwayFromPreview(row)
            }
            assertTrue("Photo picker did not return the selected image", waitUntilPackage(own, 8_000))
            compose.waitUntil(8_000) {
                compose.onAllNodesWithText("Убрать оформление").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("Не удалось прочитать изображение. Выберите другой файл.").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Убрать оформление").assertIsDisplayed()
        } finally {
            runCatching { resolver.delete(uri, null, null) }
            var backs = 0
            while (backs < 3) {
                val current = device.currentPackageName
                if (current == compose.activity.packageName || current == "com.glebskorobogatov.subscriptiontracker") break
                device.pressBack()
                backs++
            }
        }
    }

    private fun waitUntilPackage(name: String, timeoutMs: Long): Boolean {
        val start = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() - start < timeoutMs) {
            if (device.currentPackageName == name) return true
            android.os.SystemClock.sleep(200)
        }
        return false
    }

    private fun calendarDay(day: Int, months: List<String> = emptyList()) = SemanticsMatcher("calendar day $day") { node ->
        if (!node.config.contains(SemanticsActions.OnClick)) return@SemanticsMatcher false
        val label = buildString {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { append(it.text).append(' ') }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { append(it).append(' ') }
            node.config.getOrNull(SemanticsProperties.EditableText)?.let { append(it) }
        }
        Regex("\\b$day\\b").containsMatchIn(label) &&
            (months.isEmpty() || months.any { name -> label.contains(name, ignoreCase = true) || name.length > 3 && label.contains(name.dropLast(1), ignoreCase = true) })
    }

    private fun waitForPackageChange(own: String): Boolean {
        val start = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() - start < 8_000) {
            if (device.currentPackageName != own) return true
            android.os.SystemClock.sleep(200)
        }
        return false
    }

    private fun open() {
        compose.onNodeWithText("Добавить подписку").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Добавление подписки").assertIsDisplayed()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
    }

    private fun save() { compose.onNodeWithTag("editor_save").performClick() }

    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }

    private fun imeVisible(): Boolean {
        var visible = false
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.decorView.rootWindowInsets?.let {
                visible = WindowInsetsCompat.toWindowInsetsCompat(it, activity.window.decorView).isVisible(WindowInsetsCompat.Type.ime())
            }
        }
        return visible
    }

    private fun showIme() {
        compose.activityRule.scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(5_000) { imeVisible() }
    }

    private fun editorAboveIme() {
        var imeTop = 0
        compose.activityRule.scenario.onActivity { activity ->
            val inset = WindowInsetsCompat.toWindowInsetsCompat(activity.window.decorView.rootWindowInsets!!, activity.window.decorView)
                .getInsets(WindowInsetsCompat.Type.ime()).bottom
            imeTop = activity.windowManager.currentWindowMetrics.bounds.height() - inset
        }
        val editor = compose.onNodeWithTag("subscription_editor").fetchSemanticsNode().boundsInWindow
        assertTrue("Editor must end above the IME: $editor vs $imeTop", editor.bottom <= imeTop + 1)
        val save = compose.onNodeWithTag("editor_save").fetchSemanticsNode().boundsInWindow
        assertTrue("Save stays above the IME", save.bottom <= imeTop + 1)
    }

    private fun orient(request: Int, expected: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = request }
        compose.waitUntil(10_000) {
            var matches = false
            compose.activityRule.scenario.onActivity { matches = it.resources.configuration.orientation == expected }
            matches
        }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { parcel ->
            java.io.FileInputStream(parcel.fileDescriptor).bufferedReader().use { it.readText() }
        }
    }

    private fun shot(name: String) {
        val file = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "r5a-device-$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        assertTrue(file.length() > 0)
    }
}

/** Compose-only editor states. IsolatedMainActivity already owns its content. */
class R5aEditorStateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun blockedAndFailedStatesKeepTheirActions() {
        val data = DetailsEditorPreviewData.editor
        val state = androidx.compose.runtime.mutableStateOf(data.copy(loadFailure = true, error = R.string.editor_load_error))
        var retries = 0
        var reloads = 0
        var saves = 0
        compose.setContent {
            TrackerTheme {
                EditorForm(state.value, { _, _ -> }, { saves++ }, { retries++ }, { reloads++ }, {}, {})
            }
        }
        compose.onNodeWithText("Не удалось загрузить подписку.").assertIsDisplayed()
        compose.onNodeWithText("Повторить загрузку").performClick()
        compose.runOnIdle { assertEquals(1, retries); state.value = data.copy(conflict = true, error = R.string.editor_conflict) }
        compose.onNodeWithText("Перезагрузить данные").performClick()
        compose.onNodeWithTag("editor_save").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, reloads); assertEquals(0, saves); state.value = data.copy(notFound = true) }
        compose.onNodeWithText("Подписка не найдена").assertIsDisplayed()
        compose.onNodeWithTag("editor_save").assertDoesNotExist()
        compose.runOnIdle { state.value = data.copy(saving = true) }
        compose.onNodeWithText("Сохраняем…").assertIsNotEnabled()
        compose.runOnIdle { state.value = data.copy(importingImage = true) }
        compose.onNodeWithTag("editor_save").assertIsNotEnabled()
        compose.onNodeWithTag("field_name").assertIsNotEnabled()
    }

    @Test fun hiddenAnchorErrorOpensWithoutAnotherTap() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                EditorDemo(DetailsEditorPreviewData.editor.copy(errors = mapOf("anchorDay" to R.string.editor_invalid)))
            }
        }
        compose.onNodeWithTag("field_anchorDay").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("editor_save").assertIsDisplayed()
    }

    @Test fun disabledAnimationsStillShowTheSheet() {
        compose.setContent {
            CompositionLocalProvider(LocalTrackerAnimations provides false) {
                TrackerTheme {
                    TrackerBottomSheet({}, Modifier.testTag("motion_sheet")) { Text("Лист без анимации") }
                }
            }
        }
        compose.onNodeWithTag("motion_sheet").assertIsDisplayed()
        compose.onNodeWithText("Лист без анимации").assertIsDisplayed()
    }
}
