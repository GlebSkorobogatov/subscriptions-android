package com.glebskorobogatov.subscriptiontracker.ui.review

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import org.junit.Rule
import org.junit.Test

/** Output consists only of genuine Compose captures if this test is actually run on Android. */
class R4aGalleryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun rootAndNestedVisualAcceptanceMatrix() {
        data class Case(val scene: ReviewScene,val theme: Theme,val width: Int,val font: Float)
        val cases = buildList {
            for(theme in listOf(Theme.LIGHT,Theme.DARK)) {
                for(scene in listOf(ReviewScene.Subscriptions,ReviewScene.SubscriptionsEmpty,ReviewScene.SubscriptionsLong,
                    ReviewScene.SubscriptionsHidden,ReviewScene.FinanceContent,ReviewScene.Settings,ReviewScene.Archive)) {
                    add(Case(scene,theme,390,1f))
                }
                add(Case(ReviewScene.SubscriptionsLong,theme,320,2f))
            }
        }
        val current = mutableStateOf(cases.first()); var density = 1f
        compose.setContent { val c = current.value; ReviewViewport(c.scene,c.theme,c.font,c.width,onDensity = { density = it }) }
        val artifacts = ReviewArtifacts("r4a-navigation-home")
        for(c in cases) {
            compose.runOnIdle { current.value = c }; compose.waitForIdle()
            if(c.scene == ReviewScene.SubscriptionsHidden) compose.onNodeWithTag("bottom_navigation").assertIsNotDisplayed()
            else if(c.scene == ReviewScene.Archive) compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
            else compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
            artifacts.capture(compose,c.scene,c.theme.name.lowercase(),c.font,density)
        }
        artifacts.finish()
    }
}
