package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.data.integration.forecastSnapshot
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.settings.CostRankingPeriod
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerRootChrome
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTab
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.preview.FinancePreviewData
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FinanceStatisticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val today = LocalDate.of(2027, 1, 1)
    private val weekly = FinancePreviewData.rows.first().copy(intervalUnit = FrequencyUnit.WEEK,
        priceMinor = 10_000, anchorDay = 1)
    private val outside = FinancePreviewData.rows.last().copy(priceMinor = 120_000, anchorYear = 2029)
    private fun snapshot(rows: List<SubscriptionEntity>) = forecastSnapshot(rows, today)
    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Test fun cardUsesNormalizedYearAndMonthAndRankingPeriodStayIndependent() {
        val finance = snapshot(listOf(weekly, outside))
        assertEquals(530_000L, finance.forecast!!.totalMinor)
        var state by mutableStateOf(FinanceUiState(FinancePhase.Content, finance, YearMonth.from(today)))
        var period by mutableStateOf(CostRankingPeriod.MONTH)
        compose.setContent {
            TrackerTheme { FinancePane(state, onMonth = { state = state.copy(month = it) }, period = period, onPeriod = { period = it }) }
        }
        fun card() {
            scroll("finance_30_total").assertTextEquals("500 ₽")
            scroll("finance_total").assertTextEquals("6 414,29 ₽")
            compose.onNode(hasText("Расчётная стоимость за год") and hasAnyAncestor(hasTestTag("finance_summary"))).assertExists()
            compose.onNode(hasText("За месяц") and hasAnyAncestor(hasTestTag("finance_summary"))).assertDoesNotExist()
        }
        card()
        val month = "finance_month_2027-02"
        scroll("finance_month_chart")
        compose.onNodeWithTag(month).performScrollTo().performClick().assertIsSelected()
        scroll("finance_selected_total").assertTextEquals("400 ₽")
        for (date in listOf("05", "12", "19", "26")) {
            val tag = "finance_charge_${weekly.id}_2027-02-$date"
            scroll(tag)
            compose.onNode(hasText("100 ₽") and hasAnyAncestor(hasTestTag(tag))).assertIsDisplayed()
        }
        scroll("finance_ranking_amount_${weekly.id}").assertTextEquals("434,52 ₽")
        scroll("finance_ranking_amount_${outside.id}").assertTextEquals("100 ₽")
        scroll("finance_ranking_period_YEAR").performClick().assertIsSelected()
        scroll("finance_ranking_amount_${weekly.id}").assertTextEquals("5 214,29 ₽")
        scroll("finance_ranking_amount_${outside.id}").assertTextEquals("1 200 ₽")
        scroll("finance_selected_total").assertTextEquals("400 ₽")
        card()
        assertEquals(YearMonth.of(2027, 2), state.month)
    }

    @Test fun rankingKeepsSameNamedEntriesAndExactOrderWhenTheirDisplayedAmountsRoundToZero() {
        val high = outside.copy(priceMinor = 1, intervalCount = 3)
        val low = weekly.copy(priceMinor = 1, intervalUnit = FrequencyUnit.YEAR, intervalCount = 4, anchorYear = 2029)
        val ui = FinanceUiState(FinancePhase.Content, snapshot(listOf(low, high)), YearMonth.from(today))
        compose.setContent { TrackerTheme { FinancePane(ui, period = CostRankingPeriod.YEAR) } }
        scroll("finance_calendar_empty").assertIsDisplayed()
        compose.onNodeWithTag("finance_empty").assertDoesNotExist()
        scroll("finance_30_total").assertTextEquals("0 ₽")
        scroll("finance_total").assertTextEquals("1 ₽")
        scroll("finance_ranking_amount_${high.id}").assertTextEquals("0 ₽")
        val highTag = "finance_ranking_${high.id}"
        val lowTag = "finance_ranking_${low.id}"
        scroll(lowTag).assert(!hasClickAction())
        compose.onNode(hasText(low.accountLabel!!) and hasAnyAncestor(hasTestTag(lowTag))).assertIsDisplayed()
        scroll(highTag).assert(!hasClickAction())
        compose.onNode(hasText(high.accountLabel!!) and hasAnyAncestor(hasTestTag(highTag))).assertIsDisplayed()
        val highBar = compose.onNodeWithTag("finance_ranking_bar_${high.id}").fetchSemanticsNode().size.width
        val lowBar = compose.onNodeWithTag("finance_ranking_bar_${low.id}").fetchSemanticsNode().size.width
        assertTrue(highBar > lowBar && lowBar > 0)
        assertTrue(compose.onNodeWithTag(highTag).fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag(lowTag).fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun chartSavesManualScrollEvenWhenSelectedMonthIsOffscreen() {
        val value = snapshot(listOf(weekly)).forecast!!
        val restore = StateRestorationTester(compose)
        restore.setContent { TrackerTheme { MonthlyForecastChart(value.months, YearMonth.from(today)) {} } }
        fun offset() = compose.onNodeWithTag("finance_month_chart").fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        compose.onNodeWithTag("finance_month_2027-10").performScrollTo().assertIsDisplayed()
        val manual = offset()
        assertTrue(manual > 0f)
        restore.emulateSavedInstanceStateRestore()
        assertEquals(manual, offset(), 1f)
        compose.onNodeWithTag("finance_month_2027-10").assertIsDisplayed()
        compose.onNodeWithTag("finance_month_2027-01").assertIsSelected()
    }

    @Test fun rankingWriteFailureLeavesConfirmedSelectionAndOffersRetry() {
        var retries = 0
        val ui = FinanceUiState(FinancePhase.Content, snapshot(listOf(weekly)), YearMonth.from(today))
        compose.setContent {
            TrackerTheme { FinancePane(ui, periodEnabled = false, periodWriteError = true, onRetryPeriod = { retries++ }) }
        }
        scroll("finance_ranking_period_MONTH").assertIsSelected().assertIsNotEnabled()
        scroll("finance_ranking_write_error")
        compose.onNodeWithText("Повторить сохранение").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        scroll("finance_total").assertTextEquals("5 214,29 ₽")
    }

    @Test fun rankingAndLongMoneyStayReachableAcrossWidthsFontsAndThemes() {
        val large = outside.copy(name = "Длинное учебное название подписки для проверки переноса",
            priceMinor = 9_000_000_000_000_000_007L)
        val ui = FinanceUiState(FinancePhase.Content, snapshot(listOf(large)), YearMonth.from(today))
        data class Case(val width: Int = 320, val font: Float = 1f, val theme: Theme = Theme.LIGHT)
        var case by mutableStateOf(Case())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, case.font)) {
                Box(Modifier.requiredWidth(case.width.dp).fillMaxHeight()) {
                    TrackerTheme(case.theme) {
                        TrackerRootChrome(TrackerTab.Finance, {}, {}, animationsEnabled = false) {
                            key(case) { FinancePane(ui, period = CostRankingPeriod.YEAR) }
                        }
                    }
                }
            }
        }
        for (theme in listOf(Theme.LIGHT, Theme.DARK)) for (width in listOf(320, 360, 390, 412)) {
            for (font in listOf(1f, 1.5f, 2f)) {
                compose.runOnIdle { case = Case(width, font, theme) }
                scroll("finance_ranking_${large.id}").assertIsDisplayed()
                val amount = compose.onNodeWithTag("finance_ranking_amount_${large.id}").assertTextEquals("90 000 000 000 000 000,07 ₽")
                val layouts = mutableListOf<TextLayoutResult>()
                amount.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
                val last = compose.onNodeWithTag("finance_ranking_${large.id}").fetchSemanticsNode().boundsInRoot
                val island = compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().boundsInRoot
                assertTrue(last.bottom <= island.top)
                scroll("finance_ranking_period_YEAR").assertIsSelected().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            }
        }
    }
}
