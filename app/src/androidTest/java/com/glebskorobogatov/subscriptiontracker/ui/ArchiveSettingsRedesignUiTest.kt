package com.glebskorobogatov.subscriptiontracker.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.ui.archive.*
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.preview.*
import com.glebskorobogatov.subscriptiontracker.ui.settings.*
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*

/** Deterministic renderers only. Real Room/DataStore, theme and reactivation regression stays enabled. */
class ArchiveSettingsRedesignUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val data = ArchiveSettingsPreviewData
    @Before fun prepare() { compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() } }
    @Test fun duplicateArchiveNamesKeepLabelsCurrenciesAndOpenCorrectUuid() {
        val opened = mutableListOf<UUID>()
        compose.setContent { ArchiveDemo(onOpen = { opened += it }) }
        compose.onAllNodesWithText("Видеосервис").assertCountEquals(2)
        compose.onNodeWithTag("archive_${data.personal.id}").assert(hasText("Личный")).assert(hasText("799,00 RUB")).performClick()
        compose.onNodeWithTag("archive_${data.family.id}").assert(hasText("Семейный")).assert(hasText("12,99 USD")).performClick()
        compose.runOnIdle { assertEquals(listOf(data.personal.id,data.family.id),opened) }
    }
    @Test fun emptyArchiveIsMinimalAndIslandKeepsArchiveSelected() {
        compose.setContent { ArchiveDemo(ArchiveUiState(ArchivePhase.Empty)) }
        compose.onNodeWithText("В архиве пока пусто").assertIsDisplayed()
        compose.onNodeWithText("Добавить первую подписку").assertDoesNotExist()
        compose.onNodeWithTag("tab_archive").assertIsSelected()
        compose.onNodeWithContentDescription("Открыть настройки").assertHasClickAction()
    }
    @Test fun archiveLoadingErrorAndEmptyAreDistinctAndRetryWorks() {
        val state = mutableStateOf(ArchiveUiState())
        var retries = 0
        compose.setContent { ArchiveDemo(state.value,onRetry = { retries++ }) }
        compose.onNodeWithText("Загружаем архив…").assertExists()
        compose.onNodeWithText("В архиве пока пусто").assertDoesNotExist()
        compose.runOnIdle { state.value = ArchiveUiState(ArchivePhase.Error) }
        compose.onNodeWithText("В архиве пока пусто").assertDoesNotExist()
        compose.onNodeWithText("Повторить загрузку").performClick()
        compose.runOnIdle { assertEquals(1,retries);state.value = ArchiveUiState(ArchivePhase.Empty) }
        compose.onNodeWithText("В архиве пока пусто").assertExists()
    }
    @Test fun archiveShowsOnlyKnownAccessAndNoForecastOrRowReactivation() {
        compose.setContent { ArchiveDemo() }
        compose.onNodeWithText("Доступ до 2027-02-28 включительно").assertExists()
        compose.onNodeWithText("Дата окончания доступа неизвестна").assertExists()
        compose.onNodeWithText("Возобновить подписку").assertDoesNotExist()
        compose.onNodeWithText("Прогноз на 30 дней").assertDoesNotExist()
        compose.onNodeWithTag("subscription_progress").assertDoesNotExist()
    }
    @Test fun largeFontLongArchiveEntryAndNavigationStayAccessible() {
        val long = data.personal.copy(name = "Очень длинное название архивной семейной подписки",accountLabel = "Отдельный учебный аккаунт",priceMinor = 999999999)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                Box(Modifier.widthIn(max = 320.dp).fillMaxSize()) { ArchiveDemo(ArchiveUiState(ArchivePhase.Content,listOf(long))) }
            }
        }
        compose.onNodeWithTag("archive_screen").performScrollToNode(hasTestTag("archive_${long.id}"))
        compose.onNodeWithTag("archive_${long.id}").assertHasClickAction()
        compose.onNodeWithText(long.name).assertExists()
        compose.onNodeWithTag("tab_archive").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription("Открыть настройки").assertIsDisplayed()
    }
    @Test fun fixedTimeRowKeepsValueAndHasNoEditingAction() {
        compose.setContent { Box(Modifier.widthIn(max = 390.dp).fillMaxSize()) { SettingsDemo() } }
        compose.onNodeWithTag("settings_time").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Общее время: 09:00"))
        val label = compose.onNodeWithTag("settings_time_label",useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithTag("settings_time_value",useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(value.left >= label.right)
        compose.onNodeWithTag("settings_time").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithTag("settings_time_dialog").assertDoesNotExist()
    }

    @Test fun narrowLargeFontTimeLabelNeverBecomesVerticalColumn() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                Box(Modifier.widthIn(max = 320.dp).fillMaxSize()) { SettingsDemo() }
            }
        }
        compose.onNodeWithTag("settings_time").performScrollTo().assertIsDisplayed()
        val label = compose.onNodeWithTag("settings_time_label",useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1,layouts.size)
        assertFalse("The entire time label must fit without overflow",layouts.single().hasVisualOverflow)
        assertTrue("Label uses at most two natural lines",layouts.single().lineCount <= 2)
        assertTrue("Label retains meaningful horizontal width",label.fetchSemanticsNode().size.width >= 180 * compose.density.density)
        compose.onNodeWithTag("settings_time_value",useUnmergedTree = true).assertIsDisplayed().assertTextEquals("09:00")
        compose.onNodeWithContentDescription("Назад").assertIsDisplayed()
    }
    @Test fun threeThemeChoicesApplyAndKeepAccessibleSelection() {
        val state = mutableStateOf(data.settings)
        compose.setContent { SettingsDemo(state.value,state.value.data!!.theme,onTheme = { state.value = state.value.copy(data = state.value.data!!.copy(theme = it)) }) }
        for(theme in listOf(Theme.LIGHT,Theme.DARK,Theme.SYSTEM)) {
            compose.onNodeWithTag("settings_theme_${theme.name}").performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(theme,state.value.data!!.theme) }
            if(theme != Theme.SYSTEM) compose.onNodeWithTag(if(theme == Theme.DARK) "theme_dark" else "theme_light").assertExists()
        }
    }
    @Test fun oneGlobalOffsetDialogAllowsArbitrarySelectionAndEmptySet() {
        val state = mutableStateOf(data.settings); val calls = mutableListOf<Pair<Int,Boolean>>()
        compose.setContent { SettingsDemo(state.value,onOffset = { day,on ->
            calls += day to on; val settings = state.value.data!!
            state.value = state.value.copy(data = settings.copy(paymentOffsetsDefault =
                if(on) settings.paymentOffsetsDefault + day else settings.paymentOffsetsDefault - day))
        }) }
        compose.openSettingsOffsets()
        compose.onNodeWithTag("settings_payment_7").assertIsOff().performClick().assertIsOn()
        for(day in listOf(7,3,1)) compose.onNodeWithTag("settings_payment_$day").performScrollTo().performClick().assertIsOff()
        compose.onNode(hasText("Напоминания отключены.") and hasAnyAncestor(hasTestTag("settings_offsets_dialog"))).assertExists()
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithTag("settings_payment_offsets").assert(hasText("Напоминания отключены."))
        compose.onNodeWithTag("settings_cancellation_offsets").assertDoesNotExist()
        compose.runOnIdle { assertEquals(7 to true,calls.first()); assertTrue(state.value.data!!.paymentOffsetsDefault.isEmpty()) }
    }

    @Test fun offsetDialogRestoresWithoutAutomaticallySendingAnotherChange() {
        val restoration = StateRestorationTester(compose)
        var changes = 0
        restoration.setContent { SettingsDemo(onOffset = {_,_ -> changes++ }) }
        compose.openSettingsOffsets()
        compose.onNodeWithTag("settings_payment_3").assertIsOn()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("settings_offsets_dialog").assertExists()
        compose.onNodeWithTag("settings_payment_3").assertIsOn()
        compose.runOnIdle { assertEquals(0,changes) }
    }
    @Test fun settingsLoadingAndReadErrorDoNotInventPreferencesAndRetryWorks() {
        val state = mutableStateOf(SettingsUiState())
        var retries = 0
        compose.setContent { SettingsDemo(state.value,onRetryRead = { retries++ }) }
        compose.onNodeWithText("Загружаем настройки…").assertExists()
        compose.onNodeWithTag("settings_theme_SYSTEM").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(phase = SettingsPhase.Error,error = R.string.settings_load_error) }
        compose.onNodeWithTag("settings_time").assertDoesNotExist()
        compose.onNodeWithText("Повторить загрузку").performClick()
        compose.runOnIdle { assertEquals(1,retries) }
    }
    @Test fun savingAndUncertainWriteBlockFurtherChangesAndOfferExplicitRetry() {
        val state = mutableStateOf(data.settings.copy(phase = SettingsPhase.Saving,pending = true))
        var retries = 0
        compose.setContent { SettingsDemo(state.value,onRetryWrite = { retries++ }) }
        compose.onNodeWithTag("settings_theme_LIGHT").assertIsNotEnabled()
        compose.onNodeWithTag("settings_time").performScrollTo().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.runOnIdle { state.value = state.value.copy(phase = SettingsPhase.Content,error = R.string.settings_uncertain) }
        compose.onNodeWithText("Повторить сохранение").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,retries) }
        compose.onNodeWithTag("settings_payment_offsets").performScrollTo().assertIsNotEnabled()
    }
    @Test fun aboutShowsVersionAndDataInfoWithoutFakeBackupActionOrIsland() {
        var backs = 0
        compose.setContent { SettingsDemo(onBack = { backs++ }) }
        compose.onNodeWithText("Данные").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Подписки, настройки и изображения хранятся на этом устройстве.")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithTag("settings_version").performScrollTo().assertTextEquals("Версия 0.1")
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Назад").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1,backs) }
    }

    @Test fun captureLightArchiveEmptyAndSettingsBothScales() = capture(Theme.LIGHT,"light")
    @Test fun captureDarkArchiveEmptyAndSettingsBothScales() = capture(Theme.DARK,"dark")
    private fun capture(theme: Theme,name: String) {
        val mode = mutableIntStateOf(0)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,if(mode.intValue == 3) 2f else 1f)) {
                Box(Modifier.widthIn(max = if(mode.intValue == 3) 320.dp else 390.dp).fillMaxSize().testTag("ui05_capture_surface")) {
                    when(mode.intValue) {
                        0 -> ArchiveDemo(theme = theme)
                        1 -> ArchiveDemo(ArchiveUiState(ArchivePhase.Empty),theme)
                        else -> SettingsDemo(theme = theme)
                    }
                }
            }
        }
        compose.onNodeWithTag("archive_${data.personal.id}").assertExists(); screenshot("archive-$name")
        compose.runOnIdle { mode.intValue = 1 }
        compose.onNodeWithText("В архиве пока пусто").assertExists(); screenshot("archive-empty-$name")
        compose.runOnIdle { mode.intValue = 2 }
        compose.onNodeWithTag("settings_time").performScrollTo(); screenshot("settings-$name")
        compose.runOnIdle { mode.intValue = 3 }
        compose.onNodeWithTag("settings_time").performScrollTo(); screenshot("settings-large-text-$name")
    }
    private fun screenshot(name: String) {
        val directory = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val file = File(directory,"ui05-$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onNodeWithTag("ui05_capture_surface").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        assertTrue(file.length() > 0)
    }
}
