package com.glebskorobogatov.subscriptiontracker.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.io.File
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Stateless fixtures, no Room/DataStore access and no production dataset. */
class SubscriptionsRedesignUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val samples = SubscriptionsPreviewData
    @Before fun edgeToEdge() { compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() } }
    private fun start(state: SubscriptionsUiState, scale: Float = 1f, theme: Theme = Theme.LIGHT,
        onAdd: () -> Unit = {}, onSettings: () -> Unit = {}, onOpen: (UUID) -> Unit = {}, onRetry: () -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                SubscriptionsShowcase(state, theme, onAdd, onSettings, onOpen, onRetry)
            }
        }
    }
    private fun show(id: UUID): SemanticsNodeInteraction {
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasTestTag("subscription_$id"))
        return compose.onNodeWithTag("subscription_$id").assertIsDisplayed()
    }
    private fun progress(id: UUID) = compose.onNodeWithTag("subscription_progress_$id", useUnmergedTree = true)

    @Test fun loadingEmptyAndErrorAreDistinctAndActionsWork() {
        val state = mutableStateOf<SubscriptionsUiState>(SubscriptionsUiState.Loading)
        var adds = 0; var retries = 0
        compose.setContent { SubscriptionsShowcase(state.value, onAdd = { adds++ }, onRetry = { retries++ }) }
        compose.onNodeWithText("Загружаем подписки…").assertIsDisplayed()
        compose.onNodeWithText("Подписок пока нет").assertDoesNotExist()
        compose.runOnIdle { state.value = SubscriptionsUiState.Error }
        compose.onNodeWithText("Подписок пока нет").assertDoesNotExist()
        compose.onNodeWithText("Повторить загрузку").performClick()
        compose.runOnIdle { assertEquals(1, retries); state.value = SubscriptionsUiState.Empty() }
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("Добавить подписку"))
        compose.onNodeWithText("Добавить подписку").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, adds) }
    }

    @Test fun forecastRendersExactRubEstimateWithoutForeignCurrencyControls() {
        start(samples.content(active=listOf(samples.video),cancelledRows=emptyList()))
        compose.onNodeWithTag("forecast_summary").assertDoesNotExist()
        show(samples.video.id).assertTextContains("499 ₽")
        compose.onNodeWithTag("active_subscriptions_heading").assertDoesNotExist()
        compose.onNodeWithText("Валюта").assertDoesNotExist()
    }
    @Test fun duplicateNamesKeepLabelsDividersAndCorrectUuid() {
        var opened: UUID? = null
        start(samples.content(cancelledRows = emptyList()), onOpen = { opened = it })
        show(samples.video.id).assertTextContains("Личный").performClick()
        compose.runOnIdle { assertEquals(samples.video.id, opened) }
        compose.onNodeWithTag("subscription_divider_${samples.video.id}", useUnmergedTree = true).assertDoesNotExist()
        show(samples.family.id).assertTextContains("Семейный").performClick()
        compose.runOnIdle { assertEquals(samples.family.id, opened) }
        compose.onNodeWithTag("subscription_divider_${samples.family.id}", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun cancelledRowsHaveNoProblemOrAwaitingStatus() {
        start(samples.content(active=emptyList(),cancelledRows=listOf(samples.cancelled,samples.otherCancelled)))
        show(samples.cancelled.id).assertTextContains("Отменена",substring=true)
        progress(samples.cancelled.id).assertDoesNotExist()
        show(samples.otherCancelled.id).assertTextContains("Отменена",substring=true)
        compose.onNodeWithText("Проблема с оплатой").assertDoesNotExist()
        compose.onNodeWithText("Ожидает продления").assertDoesNotExist()
        compose.onNodeWithTag("active_subscriptions_heading").assertDoesNotExist()
    }
    @Test fun knownAccessIsShownOnceAndUnknownAccessHasNoProgress() {
        val known = samples.video.copy(status = SubscriptionState.CANCELLED, eventDate = LocalDate.of(2027, 6, 30), remainingDays = 15)
        val unknown = samples.family.copy(status = SubscriptionState.CANCELLED, eventDate = null, remainingDays = null, remainingFraction = null)
        start(samples.content(active = listOf(known, unknown), cancelledRows = emptyList()))
        show(known.id).assertTextContains("Доступ до 30 июн. (не включая этот день)")
        compose.onAllNodesWithText("Доступ до 30 июн. (не включая этот день)", useUnmergedTree = true).assertCountEquals(1)
        show(unknown.id).assertTextContains("Дата окончания доступа неизвестна")
        progress(unknown.id).assertDoesNotExist()
    }

    @Test fun suppliedDomainFractionIsRenderedWithThinProgressAndTextStatus() {
        start(samples.content(active = listOf(samples.video), cancelledRows = emptyList()))
        show(samples.video.id).assertTextContains("Активна").assertTextContains("17 июн. · 2 дн.")
        progress(samples.video.id).assertRangeInfoEquals(ProgressBarRangeInfo(0.75f, 0f..1f))
        val track = compose.onNodeWithTag("subscription_progress_track_${samples.video.id}", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("Visible track height must be 4dp", 4f * compose.density.density, track.size.height.toFloat(), 1f)
        assertTrue("Accessibility bounds must cover the visible track", progress(samples.video.id).fetchSemanticsNode().size.height >= track.size.height)
    }

    @Test fun largeFontKeepsLongNamesAmountsAndToolbarActionsAvailable() {
        val long = samples.video.copy(name = "Очень длинное название семейного видеосервиса", accountLabel = "Отдельный семейный аккаунт",
            price = Money(Long.MAX_VALUE, CurrencyCode.of("RUB")))
        var adds = 0; var settings = 0
        start(samples.content(active = listOf(long, samples.family), cancelledRows = emptyList()), scale = 2f,
            theme = Theme.DARK, onAdd = { adds++ }, onSettings = { settings++ })
        compose.onNodeWithContentDescription("Добавить подписку").assertIsDisplayed().performClick()
        compose.onNodeWithTag("tab_settings").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, adds); assertEquals(1, settings) }
        show(long.id).assertTextContains(long.name)
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText(com.glebskorobogatov.subscriptiontracker.ui.design.formatTrackerRub(long.price.amountMinor)))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(com.glebskorobogatov.subscriptiontracker.ui.design.formatTrackerRub(long.price.amountMinor), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Scaled amount must expose its layout", layouts.isNotEmpty())
        assertTrue("Scaled amount must not overflow: " + layouts.joinToString { "${it.size}, ${it.multiParagraph.width}x${it.multiParagraph.height}, widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}" },
            layouts.none { it.hasVisualOverflow })
        show(samples.family.id).performClick()
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
    }

    @Test fun captureLightTopListAndEmptyScreens() = captureScreens(Theme.LIGHT, "light")
    @Test fun captureDarkTopListAndEmptyScreens() = captureScreens(Theme.DARK, "dark")
    private fun captureScreens(theme: Theme, name: String) {
        val state = mutableStateOf<SubscriptionsUiState>(samples.content())
        compose.setContent { SubscriptionsShowcase(state.value, theme) }
        compose.onNodeWithTag(if (theme == Theme.DARK) "theme_dark" else "theme_light").assertExists()
        compose.onNodeWithTag("forecast_summary").assertDoesNotExist()
        compose.onNodeWithTag("root_header").assertIsDisplayed()
        saveScreenshot("$name-top")
        show(samples.family.id)
        saveScreenshot("$name-list")
        compose.runOnIdle { state.value = SubscriptionsUiState.Empty() }
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("Добавить подписку"))
        compose.onNodeWithText("Добавить подписку").assertIsDisplayed()
        saveScreenshot("$name-empty")
    }
    private fun saveScreenshot(name: String) {
        val directory = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
            "AGP additional test output directory is required to retain UI-02 screenshots"
        }
        val file = File(directory, "ui02-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("Screenshot must be written: ${file.name}", file.length() > 0)
    }
}
