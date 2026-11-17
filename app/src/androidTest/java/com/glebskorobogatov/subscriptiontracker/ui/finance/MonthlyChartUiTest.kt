package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
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
        run {
            compose.runOnIdle { ui = FinancePreviewData.state() }
            chart()
            val forecast = ui.snapshot!!.forecast
            val maximum = forecast.months.values.maxOf { it.totalMinor }
            for((month,data) in forecast.months) {
                column(month).performScrollTo().assertHasClickAction().assertContentDescriptionEquals(description(month,Money(data.totalMinor,CurrencyCode.of("RUB"))))
                val plot = compose.onNodeWithTag("finance_plot_$month",useUnmergedTree = true).fetchSemanticsNode().size.height
                val actual = compose.onNodeWithTag("finance_bar_$month",useUnmergedTree = true).fetchSemanticsNode().size.height
                val expected = java.math.BigInteger.valueOf(data.totalMinor)
                    .multiply(java.math.BigInteger.valueOf(plot.toLong())).divide(java.math.BigInteger.valueOf(maximum)).toInt()
                assertEquals(expected,actual)
            }
        }
    }
    @Test fun tapSelectsExactMonthTotalAndEventsAndCurrencyChangeCannotMixAmounts() {
        var ui by mutableStateOf(FinancePreviewData.state())
        compose.setContent { TrackerTheme { FinancePane(ui,onMonth = { ui = ui.copy(month = it) }) } }
        select(YearMonth.of(2027,2))
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_selected_total"))
        compose.onNodeWithTag("finance_selected_total").assertTextEquals("499,00 RUB")
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasText("Личный"))
        compose.onNodeWithText("Личный").assertIsDisplayed(); compose.onNodeWithText("Семейный").assertDoesNotExist()
        compose.onNodeWithTag("finance_currency_KWD").assertDoesNotExist()
        compose.onNodeWithText("Другой сервис").assertDoesNotExist()
    }
    @Test fun allTwelveMonthsIncludingZerosAndYearBoundaryAreSelectable() {
        var ui by mutableStateOf(FinancePreviewData.chartState())
        compose.setContent { TrackerTheme { FinancePane(ui,onMonth = { ui = ui.copy(month = it) }) } }
        for((month,data) in ui.snapshot!!.forecast.months) {
            select(month)
            val money = Money(data.totalMinor,CurrencyCode.of("RUB"))
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
        val result = ForecastEngine().calculate(rows.map { it.toDomain() },FinancePreviewData.day)
        compose.runOnIdle { ui = ui.copy(snapshot = FinanceSnapshot(result,rows),month = YearMonth.of(2027,2)) }
        chart(); column(YearMonth.of(2027,2)).assertIsSelected().assertContentDescriptionEquals("февраль 2027. Ожидаемые расходы: 100,00 RUB")
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_selected_total"))
        compose.onNodeWithTag("finance_selected_total").assertTextEquals("100,00 RUB")
        compose.runOnIdle { ui = FinancePreviewData.state() }
        chart(); column(YearMonth.of(2027,1)).assertContentDescriptionEquals("январь 2027. Ожидаемые расходы: 1098,00 RUB")
    }
}
