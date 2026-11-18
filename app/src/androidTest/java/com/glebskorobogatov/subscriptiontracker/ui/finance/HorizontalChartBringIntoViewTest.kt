package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.forecast.MonthlyForecast
import com.glebskorobogatov.subscriptiontracker.ui.design.LocalVerticalSafeBringIntoViewParent
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.design.verticalSafeBringIntoViewDistance
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The island height is a vertical inset. It must not move a chart column that is already fully visible. */
@OptIn(ExperimentalFoundationApi::class)
class HorizontalChartBringIntoViewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun visibleChartColumnDoesNotScrollByTheIslandHeight() {
        val months = (1..12).associate { month ->
            val key = YearMonth.of(2027, month)
            key to MonthlyForecast(key, emptyList(), 0L)
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                TrackerTheme(Theme.SYSTEM) {
                    CompositionLocalProvider(
                        LocalBringIntoViewSpec provides object : BringIntoViewSpec {
                            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                                verticalSafeBringIntoViewDistance(0f, offset, size, containerSize, 100f)
                        },
                        LocalVerticalSafeBringIntoViewParent provides object : BringIntoViewSpec {
                            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
                        },
                    ) {
                        Box(Modifier.width(300.dp)) { MonthlyForecastChart(months, null) {} }
                    }
                }
            }
        }
        compose.onNodeWithTag("finance_month_2027-03").performScrollTo()
        val offset = compose.onNodeWithTag("finance_month_chart").fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertEquals(0f, offset, 0.5f)
    }
}
