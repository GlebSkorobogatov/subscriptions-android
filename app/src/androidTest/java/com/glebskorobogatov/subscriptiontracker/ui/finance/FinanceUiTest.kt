package com.glebskorobogatov.subscriptiontracker.ui.finance

import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.preview.FinancePreviewData
import java.time.YearMonth
import org.junit.*
import org.junit.Assert.*

class FinanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun monthsSelectExactExpectedEventsWithoutCurrencyOrHorizonControls() {
        var ui by mutableStateOf(FinancePreviewData.state())
        compose.setContent { TrackerTheme { FinancePane(ui,onMonth = { ui = ui.copy(month = it) }) } }
        compose.onNodeWithTag("finance_total").assertTextEquals("6 587 ₽")
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_2027-02"))
        compose.onNodeWithTag("finance_month_2027-02").performClick().assertIsSelected()
        val event = "finance_charge_00000000-0000-0000-0000-000000000321_2027-02-28"
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag(event))
        compose.onNode(hasText("Личный") and hasAnyAncestor(hasTestTag(event))).assertIsDisplayed()
        compose.onNodeWithTag("finance_charge_00000000-0000-0000-0000-000000000322_2027-02-28").assertDoesNotExist()
        compose.onNodeWithTag("finance_currency_KWD").assertDoesNotExist()
        compose.onNodeWithTag("finance_currency_RUB").assertDoesNotExist()
        for (months in listOf(3,6,12)) compose.onNodeWithTag("finance_horizon_$months").assertDoesNotExist()
    }

    @Test fun loadingErrorRetryAndEmptyHaveDistinctSemantics() {
        var ui by mutableStateOf(FinanceUiState()); var retries = 0
        compose.setContent { TrackerTheme { FinancePane(ui,onRetry = { retries++ }) } }
        compose.onNodeWithText("Загружаем прогноз…").assertIsDisplayed(); compose.onNodeWithTag("finance_empty").assertDoesNotExist()
        compose.runOnIdle { ui = FinanceUiState(FinancePhase.Error) }
        compose.onNodeWithText("Повторить загрузку").performClick(); compose.runOnIdle { assertEquals(1,retries) }
        compose.onNodeWithTag("finance_empty").assertDoesNotExist()
        compose.runOnIdle { ui = FinanceUiState(FinancePhase.Empty) }
        compose.onNodeWithTag("finance_empty").assertIsDisplayed(); compose.onNodeWithTag("finance_total").assertDoesNotExist()
    }
    @Test fun zeroMonthIsSelectableAndLargeTextRemainsScrollableInBothThemes() {
        var theme by mutableStateOf(Theme.LIGHT)
        var ui by mutableStateOf(FinancePreviewData.state().let { old ->
            val currency = CurrencyCode.of("RUB")
            val row = FinancePreviewData.rows.first().copy(priceMinor = Long.MAX_VALUE,intervalUnit = com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit.YEAR)
            val result = com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine().calculate(listOf(row.toDomain()),FinancePreviewData.day)
            old.copy(snapshot = FinanceSnapshot(result,listOf(row)))
        })
        compose.setContent { val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                TrackerTheme(theme) { Box(Modifier.width(320.dp).fillMaxHeight()) { FinancePane(ui,onMonth = { ui = ui.copy(month = it) }) } }
            }
        }
        for(value in listOf(Theme.LIGHT,Theme.DARK)) {
            compose.runOnIdle { theme = value; ui = ui.copy(month = YearMonth.of(2027,1)) }
            compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_total"))
            val total = compose.onNodeWithTag("finance_total").assertIsDisplayed().assertTextEquals("92 233 720 368 547 758,07 ₽")
            val layouts = mutableListOf<TextLayoutResult>()
            total.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty()); layouts.forEach { assertFalse(it.hasVisualOverflow) }
            compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_2027-02"))
            compose.onNodeWithTag("finance_month_2027-02").performClick().assertIsSelected()
            compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_empty"))
            compose.onNodeWithTag("finance_month_empty").assertIsDisplayed()
        }
    }

    @Test fun successfulZeroForecastKeepsBothZeroTotalsAndAllTwelveBucketsVisible() {
        val f = com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine().calculate(emptyList(),FinancePreviewData.day)
        val ui = FinanceUiState(FinancePhase.Empty,FinanceSnapshot(f,emptyList()),YearMonth.from(FinancePreviewData.day))
        compose.setContent { TrackerTheme { FinancePane(ui) } }
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_empty"))
        compose.onNodeWithTag("finance_empty").assertIsDisplayed()
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_30_total"))
        compose.onNodeWithTag("finance_30_total").assertTextEquals("0 ₽")
        compose.onNodeWithTag("finance_total").assertTextEquals("0 ₽")
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_chart"))
        for (month in f.months.keys) compose.onNodeWithTag("finance_month_$month").assertExists()
    }
    @Test fun monthSelectionChangesDetailsButNeverThirtyDayTotal() {
        var ui by mutableStateOf(FinancePreviewData.state())
        compose.setContent { TrackerTheme { FinancePane(ui,onMonth = { ui = ui.copy(month = it) }) } }
        compose.onNodeWithTag("finance_30_total").assertTextEquals("1 597 ₽")
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_2027-02"))
        compose.onNodeWithTag("finance_month_2027-02").performClick()
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_selected_total"))
        compose.onNodeWithTag("finance_selected_total").assertTextEquals("499 ₽")
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_30_total"))
        compose.onNodeWithTag("finance_30_total").assertTextEquals("1 597 ₽")
        compose.onNodeWithTag("finance_total").assertTextEquals("6 587 ₽")
    }
}
