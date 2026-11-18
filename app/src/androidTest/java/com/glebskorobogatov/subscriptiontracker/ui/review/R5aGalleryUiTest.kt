package com.glebskorobogatov.subscriptiontracker.ui.review

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.catalog.OfflineIconCatalog
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerConfirmationDialog
import com.glebskorobogatov.subscriptiontracker.ui.editor.EditorState
import com.glebskorobogatov.subscriptiontracker.ui.preview.DetailsEditorPreviewData
import com.glebskorobogatov.subscriptiontracker.ui.preview.EditorDemo
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Emulator pixels of the editor redesign. Fixtures are synthetic and are not committed. */
class R5aGalleryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureEditorStates() {
        data class Frame(val variant: String, val theme: Theme, val width: Int, val height: Int, val font: Float,
            val night: Boolean, val editing: Boolean, val state: EditorState, val action: String = "")
        val filled = DetailsEditorPreviewData.editor
        val empty = EditorState(loading = false, fields = mapOf(
            "name" to "", "label" to "", "amount" to "", "unit" to "MONTH", "count" to "1", "date" to "2027-04-01"))
        val custom = filled.copy(fields = filled.fields + mapOf("unit" to "MONTH", "count" to "6"))
        val invalid = filled.copy(fields = filled.fields + ("amount" to "нет"), errors = mapOf("amount" to R.string.editor_invalid))
        val frames = listOf(
            Frame("create-empty", Theme.LIGHT, 390, 844, 1f, false, false, empty),
            Frame("create-filled", Theme.LIGHT, 390, 844, 1f, false, false, filled),
            Frame("edit", Theme.LIGHT, 390, 844, 1f, false, true, filled),
            Frame("dark", Theme.DARK, 390, 844, 1f, true, true, filled),
            Frame("custom", Theme.LIGHT, 390, 844, 1f, false, true, custom),
            Frame("extra", Theme.LIGHT, 390, 844, 1f, false, true, filled, action = "extra"),
            Frame("calendar", Theme.LIGHT, 390, 844, 1f, false, true, filled, action = "calendar"),
            Frame("catalog", Theme.DARK, 390, 844, 1f, true, true, filled, action = "catalog"),
            Frame("validation", Theme.LIGHT, 390, 844, 1f, false, true, invalid),
            Frame("unsaved", Theme.LIGHT, 390, 844, 1f, false, true, filled, action = "unsaved"),
            Frame("font-2", Theme.LIGHT, 320, 720, 2f, false, true, filled),
            Frame("landscape", Theme.DARK, 844, 390, 1f, true, true, custom),
            Frame("narrow", Theme.LIGHT, 320, 720, 1.5f, false, true, filled),
        )
        val current = mutableStateOf(frames.first())
        var density = 1f
        compose.setContent {
            val frame = current.value
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val original = LocalDensity.current
                val scaled = min(with(original) { maxWidth.toPx() } / frame.width, with(original) { maxHeight.toPx() } / frame.height)
                val configuration = Configuration(LocalConfiguration.current).apply {
                    fontScale = frame.font
                    screenWidthDp = frame.width
                    screenHeightDp = frame.height
                    orientation = if (frame.width > frame.height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (frame.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                SideEffect { density = scaled }
                CompositionLocalProvider(LocalDensity provides Density(scaled, frame.font), LocalConfiguration provides configuration) {
                    Box(Modifier.requiredSize(frame.width.dp, frame.height.dp).testTag("ui06_viewport")) {
                        key(frame.variant) {
                            val editor = remember { mutableStateOf(frame.state) }
                            EditorDemo(editor.value, frame.theme, editing = frame.editing,
                                onChange = { key, value -> editor.value = editor.value.copy(fields = editor.value.fields + (key to value)) })
                            if (frame.action == "unsaved") TrackerConfirmationDialog(
                                title = stringResource(R.string.editor_unsaved),
                                message = stringResource(R.string.editor_unsaved_explanation),
                                confirmText = stringResource(R.string.editor_discard),
                                dismissText = stringResource(R.string.editor_continue),
                                onConfirm = {}, onDismiss = {})
                        }
                    }
                }
            }
        }
        val artifacts = ReviewArtifacts("r5a-editor")
        for (frame in frames) {
            compose.runOnIdle { current.value = frame }
            compose.waitForIdle()
            when (frame.action) {
                "extra" -> compose.onNodeWithTag("editor_extra").performScrollTo().performClick()
                "calendar" -> compose.onNodeWithTag("date_picker_date").performScrollTo().performClick()
                "catalog" -> compose.onNodeWithTag("editor_choose_catalog").performClick()
            }
            compose.waitForIdle()
            when (frame.variant) {
                "create-empty" -> compose.onNodeWithText("Добавление подписки").assertIsDisplayed()
                "custom", "landscape" -> {
                    compose.onNodeWithTag("frequency_custom").performScrollTo().assertIsSelected()
                    compose.onNodeWithTag("field_count").performScrollTo().assertTextContains("6")
                }
                "extra" -> compose.onNodeWithTag("field_anchorDay").performScrollTo().assertTextContains("31")
                "calendar" -> compose.onNodeWithTag("editor_date_confirm").assertIsDisplayed()
                "catalog" -> {
                    OfflineIconCatalog.entries.forEach { entry ->
                        compose.onNodeWithTag("catalog_${entry.id}").performScrollTo().assertIsDisplayed()
                    }
                    compose.onNodeWithTag("catalog_pick_image").assertIsDisplayed()
                }
                "validation" -> compose.onNodeWithTag("field_amount").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
                "unsaved" -> compose.onNodeWithText("Выйти без сохранения").assertIsDisplayed()
                "font-2" -> compose.onNodeWithTag("editor_save").assertIsDisplayed()
            }
            if (frame.action != "calendar" && frame.action != "catalog" && frame.action != "unsaved")
                compose.onNodeWithTag("editor_icon").assertExists()
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
            artifacts.capture(compose, ReviewScene.EditorEdit, frame.theme.name.lowercase(), frame.font, density, frame.variant)
            if (frame.action == "catalog") compose.onNodeWithText("Закрыть каталог").performClick()
            if (frame.action == "calendar") compose.onNodeWithTag("editor_date_confirm").performClick()
        }
        artifacts.finish()
    }
}
