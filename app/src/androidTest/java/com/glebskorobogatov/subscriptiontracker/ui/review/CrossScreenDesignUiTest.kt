package com.glebskorobogatov.subscriptiontracker.ui.review

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.ColorUtils
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.ui.openSettingsOffsets
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import com.glebskorobogatov.subscriptiontracker.ui.editor.*
import com.glebskorobogatov.subscriptiontracker.ui.preview.*
import org.junit.*
import org.junit.Assert.*

class CrossScreenDesignUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun prepare() { compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() } }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun noOverflow(node: SemanticsNodeInteraction,label: String,maxLines: Int? = null) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No text layout for $label",layouts.isNotEmpty())
        for(layout in layouts) {
            assertFalse("Overflow $label: size=${layout.size}, lines=${layout.lineCount}, widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, text=${layout.layoutInput.text}",layout.hasVisualOverflow)
            if(maxLines != null) assertTrue("Vertical label $label: ${layout.lineCount} lines",layout.lineCount <= maxLines)
        }
    }
    private fun targets(density: Float,label: String) {
        val nodes = compose.onAllNodes(hasClickAction() and isEnabled()).fetchSemanticsNodes()
        for(node in nodes) {
            // Exclude partially visible scroll items; check actual expanded touch bounds, not the painted chip.
            if(node.boundsInRoot.height < node.size.height - 1 || node.boundsInRoot.width < node.size.width - 1) continue
            val touch = node.touchBoundsInRoot
            assertTrue("Small target $label ${node.config}: $touch (density=$density)",
                touch.width >= 48 * density - 1 && touch.height >= 48 * density - 1)
        }
    }
    @Test fun datePickerActionsAnnounceSpecificFieldAndDispatchSameKey() {
        val picked = mutableListOf<String>()
        compose.setContent { TrackerTheme { EditorForm(DetailsEditorPreviewData.editor,{_,_ ->},{},{},{},{},{ picked += it }) } }
        compose.onNodeWithTag("editor_extra").performScrollTo().performClick()
        for((key,label) in listOf("date" to R.string.editor_date,"start" to R.string.editor_start,"end" to R.string.editor_end)) {
            val description = compose.activity.getString(R.string.editor_pick_date_for,text(label))
            compose.onNodeWithTag("date_picker_$key").performScrollTo().assertContentDescriptionEquals(description).performClick()
        }
        compose.runOnIdle { assertEquals(listOf("date","start","end"),picked) }
    }
    @Test fun independentOverrideControlsAnnounceEventAndDayWithoutChangingOtherSet() {
        val state = mutableStateOf(DetailsEditorPreviewData.editor)
        compose.setContent { EditorDemo(state.value,onChange = { key,value -> state.value = state.value.copy(fields = state.value.fields + (key to value)) }) }
        compose.onNodeWithTag("editor_extra").performScrollTo().performClick()
        compose.onNodeWithTag("editor_inherit_PAYMENT").assertDoesNotExist()
        compose.onNodeWithTag("editor_inherit_CANCELLATION").assertDoesNotExist()
        compose.onNodeWithTag("field_currency").assertDoesNotExist()
    }
    @Test fun timeRowWidthFontAndThemeMatrixKeepsHorizontalWordsAndCurrentValue() {
        data class Case(val width: Int,val font: Float,val theme: Theme)
        val case = mutableStateOf(Case(320,1f,Theme.LIGHT))
        var density = 1f
        compose.setContent { val c = case.value;ReviewViewport(ReviewScene.Settings,c.theme,c.font,c.width,onDensity = { density = it }) }
        for(width in listOf(320,360,390,412)) for(font in listOf(1f,1.5f,2f)) for(theme in listOf(Theme.LIGHT,Theme.DARK)) {
            compose.runOnIdle { case.value = Case(width,font,theme) }
            compose.onNodeWithTag("settings_time").performScrollTo().assertIsDisplayed()
            noOverflow(compose.onNodeWithTag("settings_time_label",useUnmergedTree = true),"time $width/$font/$theme",2)
            noOverflow(compose.onNodeWithTag("settings_time_subtitle",useUnmergedTree = true),"time subtitle $width/$font/$theme")
            noOverflow(compose.onNodeWithTag("settings_time_value",useUnmergedTree = true),"time value $width/$font/$theme",1)
            compose.onNodeWithTag("settings_time_value",useUnmergedTree = true).assertTextEquals("09:00")
            val label = compose.onNodeWithTag("settings_time_label",useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val value = compose.onNodeWithTag("settings_time_value",useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            if(font == 1f) assertTrue("Time on right $width",value.left >= label.right)
            targets(density,"time $width/$font/$theme")
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        }
    }
    @Test fun longAmountsKeepZeroTwoAndThreeFractionDigitsAcrossWidthsAndFonts() {
        data class Case(val width: Int,val font: Float,val theme: Theme,val scene: ReviewScene)
        val case = mutableStateOf(Case(320,1f,Theme.LIGHT,ReviewScene.SubscriptionsLong))
        compose.setContent { val c = case.value;ReviewViewport(c.scene,c.theme,c.font,c.width) }
        for(width in listOf(320,360,390,412)) for(font in listOf(1f,1.5f,2f)) for(theme in listOf(Theme.LIGHT,Theme.DARK)) {
            compose.runOnIdle { case.value = Case(width,font,theme,ReviewScene.SubscriptionsLong) }
            for(money in ReviewData.amounts) {
                val node = compose.onNodeWithTag("forecast_${money.currency.value}",useUnmergedTree = true)
                node.assertTextEquals("${money.format()} ${money.currency.value}")
                noOverflow(node,"money $width/$font/$theme/${money.currency.value}")
            }
            compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasTestTag("subscription_${ReviewData.longRow.id}"))
            noOverflow(compose.onNodeWithText(ReviewData.longName,useUnmergedTree = true),"subscription name $width/$font/$theme")
            val island = compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().boundsInRoot
            val list = compose.onNodeWithTag("subscriptions_screen").fetchSemanticsNode().boundsInRoot
            assertTrue("Content must end above island",list.bottom <= island.top)
            compose.runOnIdle { case.value = Case(width,font,theme,ReviewScene.DetailsLong) }
            val price = compose.onNodeWithText("92233720368547758,07 RUB",useUnmergedTree = true)
            price.performScrollTo();noOverflow(price,"details money $width/$font/$theme")
            val name = compose.onNodeWithText(ReviewData.longName,useUnmergedTree = true)
            name.performScrollTo();noOverflow(name,"details name $width/$font/$theme")
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        }
    }
    @Test fun editorErrorAndTargetsRemainAccessibleAcrossWidthsAndFonts() {
        data class Case(val width: Int,val font: Float)
        val case = mutableStateOf(Case(320,1f));var density = 1f
        compose.setContent { val c = case.value;ReviewViewport(ReviewScene.EditorError,Theme.DARK,c.font,c.width,onDensity = { density = it }) }
        for(width in listOf(320,360,390,412)) for(font in listOf(1f,1.5f,2f)) {
            compose.runOnIdle { case.value = Case(width,font) }
            compose.onNodeWithTag("field_amount").performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            targets(density,"editor $width/$font")
            compose.onNodeWithText("Сохранить").performScrollTo().assertIsDisplayed().assertHasClickAction()
        }
    }
    @Test fun systemThemeChangesConfigurationWithoutChangingExplicitThemes() {
        val night = mutableStateOf(false);val theme = mutableStateOf(Theme.SYSTEM)
        compose.setContent { ReviewViewport(ReviewScene.Settings,theme.value,systemNight = night.value) }
        compose.onNodeWithTag("theme_light").assertExists()
        compose.runOnIdle { night.value = true };compose.onNodeWithTag("theme_dark").assertExists()
        compose.runOnIdle { theme.value = Theme.LIGHT };compose.onNodeWithTag("theme_light").assertExists()
        compose.runOnIdle { theme.value = Theme.DARK;night.value = false };compose.onNodeWithTag("theme_dark").assertExists()
    }
    @Test fun normalTextAndButtonsMeetContrastRatiosInBothTokenPalettes() {
        for(c in listOf(LightTrackerColors,DarkTrackerColors)) {
            for(background in listOf(c.background,c.surface,c.surfaceSubtle,c.surfaceRaised)) {
                assertTrue("Primary text AA",ColorUtils.calculateContrast(c.textPrimary.toArgb(),background.toArgb()) >= 4.5)
                assertTrue("Secondary text AA",ColorUtils.calculateContrast(c.textSecondary.toArgb(),background.toArgb()) >= 4.5)
                assertTrue("Error text AA",ColorUtils.calculateContrast(c.error.toArgb(),background.toArgb()) >= 4.5)
            }
            assertTrue("Button text AA",ColorUtils.calculateContrast(c.primaryButtonText.toArgb(),c.primaryButtonBg.toArgb()) >= 4.5)
        }
    }
    @Test fun landscapeRootIslandAndEmptyActionStaySeparatedAtLargeFont() {
        orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,Configuration.ORIENTATION_LANDSCAPE)
        try {
            val scene = mutableStateOf(ReviewScene.SubscriptionsEmpty);var density = 1f
            compose.setContent { ReviewViewport(scene.value,Theme.LIGHT,2f,onDensity = { density = it }) }
            val artifacts = ReviewArtifacts("landscape-roots-light")
            for(s in listOf(ReviewScene.SubscriptionsEmpty,ReviewScene.ArchiveEmpty,ReviewScene.FinancePlaceholder)) {
                compose.runOnIdle { scene.value = s }
                compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
                if(s == ReviewScene.SubscriptionsEmpty) {
                    compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("Добавить первую подписку"))
                    compose.onNodeWithText("Добавить первую подписку").assertIsDisplayed().assertHasClickAction()
                }
                if(s != ReviewScene.FinancePlaceholder) {
                    val tag = if(s == ReviewScene.SubscriptionsEmpty) "subscriptions_screen" else "archive_screen"
                    val content = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    val island = compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().boundsInRoot
                    assertTrue("Landscape content must not overlap island: $s",content.bottom <= island.top)
                }
                targets(density,"landscape root $s")
                artifacts.capture(compose,s,"light",2f,density)
            }
            artifacts.finish()
        } finally { orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,Configuration.ORIENTATION_PORTRAIT) }
    }
    @Test fun galleryAllCurrentScreensLight() = gallery(Theme.LIGHT,1f)
    @Test fun galleryAllCurrentScreensDark() = gallery(Theme.DARK,1f)
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
