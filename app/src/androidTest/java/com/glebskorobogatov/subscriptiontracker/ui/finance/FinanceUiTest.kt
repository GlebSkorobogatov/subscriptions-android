package com.glebskorobogatov.subscriptiontracker.ui.finance

import com.glebskorobogatov.subscriptiontracker.data.payment.toDomain
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
    @Test fun monthsSelectExactExpectedEventsAndCurrenciesNeverMix() {
        var ui by mutableStateOf(FinancePreviewData.state())
        compose.setContent { TrackerTheme { FinancePane(ui,onMonth = { ui = ui.copy(month = it) },
            onHorizon = { ui = FinancePreviewData.state(ui.currency!!,it) },onCurrency = { ui = FinancePreviewData.state(it,ui.snapshot!!.horizon) }) } }
        compose.onNodeWithTag("finance_total").assertTextEquals("2096,00 RUB")
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_2027-02"))
        compose.onNodeWithTag("finance_month_2027-02").performClick().assertIsSelected()
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_charge_00000000-0000-0000-0000-000000000321_1"))
        compose.onNodeWithText("Личный").assertIsDisplayed()
        compose.onNodeWithText("Семейный").assertDoesNotExist()
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_currency_KWD"))
        compose.onNodeWithTag("finance_currency_KWD").performClick().assertIsSelected()
        compose.onNodeWithTag("finance_total").assertTextEquals("37,035 KWD")
        compose.onNodeWithTag("finance_horizon_6").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("finance_total").performScrollTo().assertTextEquals("74,070 KWD")
        compose.onNodeWithTag("finance_horizon_12").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("finance_total").performScrollTo().assertTextEquals("148,140 KWD")
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
            val currency = CurrencyCode.of("JPY")
            val row = FinancePreviewData.rows.first().copy(currencyCode = currency,priceMinor = Long.MAX_VALUE,renewalMode = com.glebskorobogatov.subscriptiontracker.domain.lifecycle.RenewalMode.MANUAL)
            val result = com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine().calendarMonths(listOf(row.toDomain()),FinancePreviewData.day,3,currency)
            old.copy(snapshot = FinanceSnapshot(result,listOf(row),3,currency),currency = currency)
        })
        compose.setContent { val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                TrackerTheme(theme) { Box(Modifier.width(320.dp).fillMaxHeight()) { FinancePane(ui,onMonth = { ui = ui.copy(month = it) }) } }
            }
        }
        for(value in listOf(Theme.LIGHT,Theme.DARK)) {
            compose.runOnIdle { theme = value; ui = ui.copy(month = YearMonth.of(2027,1)) }
            compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_total"))
            val total = compose.onNodeWithTag("finance_total").assertIsDisplayed().assertTextEquals("9223372036854775807 JPY")
            val layouts = mutableListOf<TextLayoutResult>()
            total.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty()); layouts.forEach { assertFalse(it.hasVisualOverflow) }
            compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_2027-02"))
            compose.onNodeWithTag("finance_month_2027-02").performClick().assertIsSelected()
            compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_empty"))
            compose.onNodeWithTag("finance_month_empty").assertIsDisplayed()
        }
    }
}
