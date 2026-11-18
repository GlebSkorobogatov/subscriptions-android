package com.glebskorobogatov.subscriptiontracker.ui.review

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.app.LargeFontReviewActivity
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.ui.openSettingsOffsets
import com.glebskorobogatov.subscriptiontracker.ui.editor.CatalogPickerDialog
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import org.junit.*
import org.junit.Assert.*

/** A genuine Activity resource context at fontScale=2; native Dialog windows inherit it too. */
class LargeFontWindowUiTest {
    @get:Rule val compose = createAndroidComposeRule<LargeFontReviewActivity>()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun targets(density: Float,label: String) {
        for(node in compose.onAllNodes(hasClickAction() and isEnabled()).fetchSemanticsNodes()) {
            if(node.boundsInRoot.height < node.size.height - 1 || node.boundsInRoot.width < node.size.width - 1) continue
            val touch = node.touchBoundsInRoot
            assertTrue("Small target $label ${node.config}: $touch",touch.width >= 48*density-1 && touch.height >= 48*density-1)
        }
    }
    private fun nativeScale(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Native window text must have a layout",layouts.isNotEmpty())
        for(layout in layouts) assertEquals("The native window must really use fontScale=2",2f,layout.layoutInput.density.fontScale,0f)
    }
    @Test fun landscapeDialogsKeepConfirmationAndCancelReachableAtLargeFont() {
        orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,Configuration.ORIENTATION_LANDSCAPE)
        try {
            val scene = mutableStateOf(ReviewScene.ReactivateDialog);var density = 1f
            compose.setContent { ReviewViewport(scene.value,Theme.DARK,2f,onDensity = { density = it }) }
            val artifacts = ReviewArtifacts("landscape-dark")
            for((s,action,cancel) in listOf(

                Triple(ReviewScene.DeleteDialog,"Подтвердить","Отмена"),
                Triple(ReviewScene.EditEndDialog,"Подтвердить","Отмена"),
                Triple(ReviewScene.DisableDialog,"Подтвердить","Отмена"),Triple(ReviewScene.ReactivateDialog,"Подтвердить","Отмена"),
                Triple(ReviewScene.ArchiveDialog,"Подтвердить","Отмена"),

                Triple(ReviewScene.UnsavedDialog,text(R.string.editor_discard),text(R.string.editor_continue)))) {
                compose.runOnIdle { scene.value = s }
                compose.onNodeWithText(action).assertIsDisplayed().assertHasClickAction()
                compose.onNodeWithText(cancel).assertIsDisplayed().assertHasClickAction()
                val field = when(s) {
                    ReviewScene.ReactivateDialog -> "details_field_amount"
                    ReviewScene.EditEndDialog -> "details_field_date"
                    ReviewScene.DisableDialog -> "details_field_end"
                    else -> null
                }
                field?.let {
                    val node = compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
                    assertTrue("Usable field height in $s must be >=48dp, actual=${node.boundsInRoot.height / density}",node.boundsInRoot.height >= 48 * density - 1)
                }
                targets(density,"landscape $s")
                artifacts.capture(compose,s,"dark",2f,density)
            }
            compose.runOnIdle { scene.value = ReviewScene.Settings }
            compose.openSettingsOffsets()
            val checkbox = compose.onNodeWithTag("settings_payment_0").performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            assertTrue("Cancellation checkbox must remain usable in landscape",checkbox.boundsInRoot.height >= 48 * density - 1)
            artifacts.capture(compose,ReviewScene.Settings,"dark",2f,density)
            compose.onNodeWithText("Готово").assertIsDisplayed().performClick()
            artifacts.finish()
        } finally { orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,Configuration.ORIENTATION_PORTRAIT) }
    }
    @Test fun galleryNarrowLargeFontLight() = gallery(Theme.LIGHT,2f)
    @Test fun galleryNarrowLargeFontDark() = gallery(Theme.DARK,2f)
    @Test fun catalogRemainsSelectableInLandscapeAtNativeDoubleFontScale() {
        orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        try {
            var selected by mutableStateOf("generic-ai")
            var density = 1f
            compose.setContent {
                density = androidx.compose.ui.platform.LocalDensity.current.density
                TrackerTheme(Theme.DARK) {
                    CatalogPickerDialog(selected, {}, { id -> selected = id })
                }
            }
            val item = compose.onNodeWithTag("catalog_generic-other").performScrollTo().assertIsDisplayed().assertHasClickAction()
            assertTrue(item.fetchSemanticsNode().touchBoundsInRoot.width >= 48 * density - 1)
            assertTrue(item.fetchSemanticsNode().touchBoundsInRoot.height >= 48 * density - 1)
            item.performClick().assertIsSelected()
            compose.onNodeWithText("Закрыть каталог").assertIsDisplayed()
        } finally { orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT) }
    }
    private fun gallery(theme: Theme,font: Float) {
        val scene = mutableStateOf(ReviewScene.Subscriptions);var density = 1f
        compose.setContent { ReviewViewport(scene.value,theme,font,onDensity = { density = it }) }
        val name = theme.name.lowercase();val artifacts = ReviewArtifacts("portrait-$name-f$font")
        val scenes = if(font == 1f) ReviewScene.entries else listOf(ReviewScene.SubscriptionsLong,ReviewScene.DetailsLong,
            ReviewScene.EditorError,ReviewScene.Settings,ReviewScene.Archive)
        for(s in scenes) {
            compose.runOnIdle { scene.value = s }
            when(s) {
                ReviewScene.Settings -> compose.onNodeWithTag("settings_time").performScrollTo()
                ReviewScene.EditorError -> compose.onNodeWithTag("field_amount").performScrollTo()
                ReviewScene.DetailsLong -> compose.onNodeWithText("92233720368547758,07 RUB").performScrollTo()
                else -> Unit
            }
            artifacts.capture(compose,s,name,font,density)
            if(s == ReviewScene.SubscriptionsLong) {
                compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasTestTag("subscription_${ReviewData.longRow.id}"))
                artifacts.capture(compose,s,name,font,density,"rows")
            }
        }
        artifacts.finish()
    }
    private fun orient(request: Int,expected: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = request }
        compose.waitUntil(10_000) {
            var matches = false;compose.activityRule.scenario.onActivity { matches = it.resources.configuration.orientation == expected };matches
        }
    }
}
