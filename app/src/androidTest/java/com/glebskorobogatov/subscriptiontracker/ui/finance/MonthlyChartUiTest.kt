package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.glebskorobogatov.subscriptiontracker.data.payment.toDomain
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.preview.FinancePreviewData
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.*
import org.junit.Assert.*

class MonthlyChartUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val format = DateTimeFormatter.ofPattern("LLLL yyyy",Locale.forLanguageTag("ru"))
    private fun chart() = compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_chart"))
    private fun column(month: YearMonth) = compose.onNodeWithTag("finance_month_$month")
    private fun select(month: YearMonth) { chart(); column(month).performScrollTo().assertIsDisplayed().performClick().assertIsSelected() }
    private fun description(month: YearMonth,money: Money) = "${month.format(format)}. Ожидаемые расходы: ${money.format()} ${money.currency.value}"
    @Test fun everyHorizonMatchesForecastAmountsAndPixelProportions() {
        var ui by mutableStateOf(FinancePreviewData.state())
        compose.setContent { TrackerTheme { FinancePane(ui,onMonth = { ui = ui.copy(month = it) }) } }
        for(count in listOf(3,6,12)) {
            compose.runOnIdle { ui = FinancePreviewData.state(months = count) }
            chart()
            val forecast = ui.snapshot!!.forecast
            val maximum = forecast.months.values.maxOf { it.totalsByCurrency.getValue(ui.currency!!).amountMinor }
            for((month,data) in forecast.months) {
                column(month).performScrollTo().assertHasClickAction().assertContentDescriptionEquals(description(month,data.totalsByCurrency.getValue(ui.currency!!)))
                val plot = compose.onNodeWithTag("finance_plot_$month",useUnmergedTree = true).fetchSemanticsNode().size.height
                val actual = compose.onNodeWithTag("finance_bar_$month",useUnmergedTree = true).fetchSemanticsNode().size.height
                val expected = java.math.BigInteger.valueOf(data.totalsByCurrency.getValue(ui.currency!!).amountMinor)
                    .multiply(java.math.BigInteger.valueOf(plot.toLong())).divide(java.math.BigInteger.valueOf(maximum)).toInt()
                assertEquals(expected,actual)
            }
        }
    }
    @Test fun tapSelectsExactMonthTotalAndEventsAndCurrencyChangeCannotMixAmounts() {
        var ui by mutableStateOf(FinancePreviewData.state())
        compose.setContent { TrackerTheme { FinancePane(ui,onMonth = { ui = ui.copy(month = it) },onCurrency = { ui = FinancePreviewData.state(it) }) } }
        select(YearMonth.of(2027,2))
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_selected_total"))
        compose.onNodeWithTag("finance_selected_total").assertTextEquals("499,00 RUB")
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasText("Личный"))
        compose.onNodeWithText("Личный").assertIsDisplayed(); compose.onNodeWithText("Семейный").assertDoesNotExist()
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_currency_KWD"))
        compose.onNodeWithTag("finance_currency_KWD").performClick()
        select(YearMonth.of(2027,2))
        column(YearMonth.of(2027,2)).assertContentDescriptionEquals("февраль 2027. Ожидаемые расходы: 12,345 KWD")
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_selected_total"))
        compose.onNodeWithTag("finance_selected_total").assertTextEquals("12,345 KWD")
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasText("Другой сервис"))
        compose.onNodeWithText("Другой сервис").assertIsDisplayed(); compose.onNodeWithText("Личный").assertDoesNotExist()
    }
    @Test fun allTwelveMonthsIncludingZerosAndYearBoundaryAreSelectable() {
        var ui by mutableStateOf(FinancePreviewData.chartState(12))
        compose.setContent { TrackerTheme { FinancePane(ui,onMonth = { ui = ui.copy(month = it) }) } }
        for((month,data) in ui.snapshot!!.forecast.months) {
            select(month)
            val money = data.totalsByCurrency[ui.currency] ?: Money(0,ui.currency!!)
            column(month).assertContentDescriptionEquals(description(month,money))
            if(money.amountMinor == 0L) {
                assertEquals(0,compose.onNodeWithTag("finance_bar_$month",useUnmergedTree = true).fetchSemanticsNode().size.height)
                compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_month_empty"))
                compose.onNodeWithTag("finance_month_empty").assertIsDisplayed()
            }
        }
        chart()
        column(YearMonth.of(2026,12)).assertContentDescriptionEquals("декабрь 2026. Ожидаемые расходы: 0,00 RUB")
        column(YearMonth.of(2027,1)).assertContentDescriptionEquals("январь 2027. Ожидаемые расходы: 0,00 RUB")
    }
    @Test fun reactivePriceAndCurrencySnapshotsUpdateBarsWithoutStaleCachedSums() {
        var ui by mutableStateOf(FinancePreviewData.state())
        compose.setContent { TrackerTheme { FinancePane(ui,onMonth = { ui = ui.copy(month = it) }) } }
        select(YearMonth.of(2027,2))
        val rows = FinancePreviewData.rows.map { if(it.id == FinancePreviewData.rows.first().id) it.copy(priceMinor = 10000) else it }
        val result = ForecastEngine().calendarMonths(rows.map { it.toDomain() },FinancePreviewData.day,3,FinancePreviewData.rub)
        compose.runOnIdle { ui = ui.copy(snapshot = FinanceSnapshot(result,rows,3,ui.currency),month = YearMonth.of(2027,2)) }
        chart(); column(YearMonth.of(2027,2)).assertIsSelected().assertContentDescriptionEquals("февраль 2027. Ожидаемые расходы: 100,00 RUB")
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_selected_total"))
        compose.onNodeWithTag("finance_selected_total").assertTextEquals("100,00 RUB")
        compose.runOnIdle { ui = FinancePreviewData.state(CurrencyCode.of("KWD")) }
        chart(); column(YearMonth.of(2027,1)).assertContentDescriptionEquals("январь 2027. Ожидаемые расходы: 12,345 KWD")
    }
}
