package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.ui.editor.EditorForm
import com.glebskorobogatov.subscriptiontracker.ui.editor.EditorState
import com.glebskorobogatov.subscriptiontracker.ui.editor.CatalogPickerDialog
import com.glebskorobogatov.subscriptiontracker.data.catalog.OfflineIconCatalog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Stateless UI fixtures: no Room, DataStore or production dataset is opened. */
class DesignSystemUiTest {
    @get:Rule val compose = createComposeRule()

    private fun navigation(scale: Float = 1f, width: Int = 360) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                Box(Modifier.width(width.dp).height(480.dp).testTag("host")) {
                    TrackerTheme {
                        var selected by remember { mutableStateOf(TrackerTab.Subscriptions) }
                        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
                            TrackerBottomNavigation(selected, { selected = it }, windowInsets = WindowInsets(bottom = 24.dp))
                        }) { padding ->
                            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("test_list"),
                                contentPadding = PaddingValues(16.dp)) {
                                items(100) { Text("Строка $it", Modifier.fillMaxWidth().padding(8.dp).testTag("row_$it")) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun tabsExposeNamesRolesAndExactlyOneSelection() {
        navigation()
        for ((tab, label) in listOf(TrackerTab.Subscriptions to "Подписки", TrackerTab.Finance to "Статистика", TrackerTab.Settings to "Настройки")) {
            compose.onNodeWithTag(tab.tag).assertContentDescriptionEquals(label).assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick().assertIsSelected()
            compose.onAllNodes(isSelected()).assertCountEquals(1)
        }
    }

    @Test fun islandReservesInsetsAndLastScrollableItemStaysAboveIt() {
        navigation()
        val density = compose.density.density
        val host = compose.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        val island = compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().boundsInRoot
        assertEquals((host.width-204f*density)/2,island.left-host.left,1f)
        assertEquals((host.width-204f*density)/2,host.right-island.right,1f)
        assertEquals(36f * density, host.bottom - island.bottom, 1f)
        assertEquals(60f * density,island.height,1f)
        compose.onNodeWithTag("test_list").performScrollToIndex(99)
        compose.onNodeWithTag("row_99").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("row_99").fetchSemanticsNode().boundsInRoot.bottom < island.top)
    }

    @Test fun narrowLargeFontKeepsLabelsAndTouchTargetsAvailable() {
        navigation(scale = 2f, width = 320)
        for (tab in TrackerTab.entries) {
            val node = compose.onNodeWithTag(tab.tag).assertIsDisplayed().assertHasClickAction()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$tab touch width ${bounds.width}px must be at least 48dp", bounds.width >= 48 * compose.density.density)
            assertTrue("$tab touch height ${bounds.height}px must be at least 48dp", bounds.height >= 48 * compose.density.density)
            val label = when(tab) { TrackerTab.Subscriptions -> "Подписки"; TrackerTab.Finance -> "Статистика"; TrackerTab.Settings -> "Настройки" }
            node.assertContentDescriptionEquals(label)
            compose.onAllNodesWithText(label).assertCountEquals(0) // icons only, accessible labels remain.
            node.performClick().assertIsSelected()
        }
    }

    @Test fun wideIslandHasBoundedWidth() {
        // The actual test window can be smaller than a tablet; requiredWidth exercises the cap.
        compose.setContent {
            TrackerTheme { Box(Modifier.requiredWidth(840.dp)) {
                TrackerBottomNavigation(TrackerTab.Settings, {}, windowInsets = WindowInsets(0, 0, 0, 0))
            } }
        }
        assertEquals(204f * compose.density.density,
            compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().size.width.toFloat(), 1f)
    }

    @Test fun genericFallbackFitsItsIconAtDoubleFontScale() {
        var measured = androidx.compose.ui.unit.IntSize.Zero
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                TrackerTheme {
                    Box(Modifier.fillMaxSize()) {
                        Layout(content = { SubscriptionThemeIcon(null, 46.dp) }) { children, constraints ->
                            val icon = children.single().measure(constraints)
                            measured = androidx.compose.ui.unit.IntSize(icon.width, icon.height)
                            layout(icon.width, icon.height) { icon.place(0, 0) }
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            val expected = 46f * compose.density.density
            assertEquals(expected, measured.width.toFloat(), 1f)
            assertEquals(expected, measured.height.toFloat(), 1f)
        }
    }

    @Test fun editorLoadingHasVisibleRussianExplanation() {
        compose.setContent {
            TrackerTheme {
                EditorForm(EditorState(loading = true), { _, _ -> }, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Загружаем форму подписки…").assertIsDisplayed()
    }

    @Test fun allGenericIconsStayReachableAndSelectedInBothThemes() {
        var theme by mutableStateOf(Theme.LIGHT)
        var selected by mutableStateOf(OfflineIconCatalog.OTHER_ID)
        compose.setContent { TrackerTheme(theme) { CatalogPickerDialog(selected, {}, { selected = it }) } }
        for (mode in listOf(Theme.LIGHT, Theme.DARK)) {
            compose.runOnIdle { theme = mode }
            OfflineIconCatalog.entries.forEach { entry ->
                compose.onNodeWithTag("catalog_${entry.id}").performScrollTo().assertIsDisplayed().assertHasClickAction()
            }
            compose.onNodeWithTag("catalog_generic-other").performClick().assertIsSelected()
            compose.onNodeWithTag("catalog_pick_image").assertHasClickAction()
        }
    }

    @Test fun fieldExposesLabelErrorAndKeepsEnteredText() {
        compose.setContent {
            TrackerTheme { Column {
                var text by remember { mutableStateOf("") }
                TrackerTextField(text, { text = it }, "Название подписки", errorMessage = "Введите название")
                TrackerPrimaryButton("Сохранить", {}, enabled = false)
            } }
        }
        val field = compose.onNodeWithContentDescription("Название подписки")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        field.performTextInput("Личный")
        field.assertTextContains("Личный")
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
    }

    @Test fun destructiveDialogCancelDoesNotConfirmAndBusyBlocksActions() {
        var confirmed = 0; var dismissed = 0
        val busy = mutableStateOf(false)
        compose.setContent {
            TrackerTheme(Theme.DARK) {
                TrackerConfirmationDialog("Удалить подписку?", "Удаление необратимо.", "Удалить", "Отмена",
                    { confirmed++ }, { dismissed++ }, destructive = true, busy = busy.value)
            }
        }
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(0, confirmed); assertEquals(1, dismissed); busy.value = true }
        compose.onNodeWithText("Удалить").assertIsNotEnabled()
        compose.onNodeWithText("Отмена").assertIsNotEnabled()
    }
}
