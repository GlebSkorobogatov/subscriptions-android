package com.glebskorobogatov.subscriptiontracker.ui.review

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import org.junit.Rule
import org.junit.Test

/** Produces actual Compose pixels only when run on Android. No generated/mock screenshots. */
class R3GalleryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun lightDarkAndNarrowLargeFontFoundationArtifacts() {
        data class Case(val theme: Theme,val width: Int,val scale: Float)
        val cases = listOf(Case(Theme.LIGHT,390,1f),Case(Theme.DARK,390,1f),
            Case(Theme.LIGHT,320,2f),Case(Theme.DARK,320,2f))
        val current = mutableStateOf(cases.first())
        var density = 1f
        compose.setContent {
            val c = current.value
            ReviewViewport(ReviewScene.DesignSystem,c.theme,c.scale,c.width,onDensity = { density = it })
        }
        val artifacts = ReviewArtifacts("r3-foundations")
        for(c in cases) {
            compose.runOnIdle { current.value = c }
            val sections = listOf("Палитра","Типографика","Поверхности","Действия","Поля","Капсульный выбор",
                "Индикаторы · расчётный период","Общие состояния")
            for((index,section) in sections.withIndex()) {
                compose.onNodeWithText(section).performScrollTo().assertIsDisplayed()
                compose.waitForIdle()
                artifacts.capture(compose,ReviewScene.DesignSystem,c.theme.name.lowercase(),c.scale,density,"section-$index")
            }
        }
        artifacts.finish()
    }
}
