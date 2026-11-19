package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.domain.forecast.MonthlyForecast
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

internal class CalendarChartState(
    val scroll: ScrollState = ScrollState(0),
    var stridePx: Int = 0,
    var revealedSelection: String? = null,
)

@Composable
internal fun rememberCalendarChartState() = rememberSaveable(saver = listSaver(
    save = { state: CalendarChartState -> listOf(state.scroll.value, state.stridePx, state.revealedSelection.orEmpty()) },
    restore = { CalendarChartState(ScrollState(it[0] as Int), it[1] as Int, (it[2] as String).ifEmpty { null }) },
)) { CalendarChartState() }

@Composable
internal fun MonthlyForecastChart(
    months: Map<YearMonth,MonthlyForecast>,
    selectedMonth: YearMonth?,
    chart: CalendarChartState = rememberCalendarChartState(),
    onMonth: (YearMonth) -> Unit,
) {
    val entries = months.entries.toList()
    val maximum = entries.maxOfOrNull { it.value.totalMinor } ?: 0L
    val fullMonth = remember { DateTimeFormatter.ofPattern("LLLL yyyy",Locale.forLanguageTag("ru")) }
    val shortMonth = remember { DateTimeFormatter.ofPattern("LLL",Locale.forLanguageTag("ru")) }
    val scroll = chart.scroll
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val plotHeight = if(windowHeight > 0.dp && windowHeight < 480.dp) 96.dp else 128.dp
    var viewportPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = TrackerDimensions.itemSpacing
        val minimumWidth = 80.dp * density.fontScale.coerceAtLeast(1f)
        val visibleLimit = if (maxWidth >= 600.dp && density.fontScale < 1.5f) 6 else 3
        val wholeColumns = ((maxWidth - gap) / (minimumWidth + gap)).toInt().coerceIn(1, visibleLimit)
        val width = maxOf(minimumWidth, (maxWidth - gap * wholeColumns) / (wholeColumns + 0.25f))
        val widthPx = with(density) { width.roundToPx() }
        val gapPx = with(density) { gap.roundToPx() }
        val selection = selectedMonth?.let { "${entries.firstOrNull()?.key}/$it" }
        val stride = widthPx + gapPx
        LaunchedEffect(selection, stride, viewportPx) {
            if (viewportPx <= 0) return@LaunchedEffect
            snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
            // Keep the same leading column when a new viewport changes column widths.
            if (chart.stridePx > 0 && chart.stridePx != stride) {
                val position = scroll.value.toDouble() / chart.stridePx
                scroll.scrollTo((position * stride).roundToInt().coerceIn(0, scroll.maxValue))
            }
            chart.stridePx = stride
            val index = entries.indexOfFirst { it.key == selectedMonth }
            // A restored scroll offset can intentionally leave the selected month off-screen.
            if (index >= 0 && selection != chart.revealedSelection) {
                val left = index * stride
                val right = left + widthPx
                val target = when {
                    left < scroll.value -> left
                    right > scroll.value + viewportPx -> right - viewportPx
                    else -> scroll.value
                }
                scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
                chart.revealedSelection = selection
            }
        }
        WithoutVerticalSafeBringIntoView {
            Row(Modifier.fillMaxWidth().onSizeChanged { viewportPx = it.width }
                .horizontalScroll(scroll).testTag("finance_month_chart").semantics { isTraversalGroup = true },
                horizontalArrangement = Arrangement.spacedBy(gap)) {
                entries.forEach { (month,data) ->
                    val active = month == selectedMonth
                    val description = stringResource(R.string.finance_chart_column,month.format(fullMonth),formatTrackerRub(data.totalMinor))
                    val choose = stringResource(R.string.finance_choose_month)
                    val color = if(active) TrackerDesign.colors.textPrimary else TrackerDesign.colors.textSecondary
                    Column(Modifier.width(width).heightIn(min = TrackerDimensions.minimumTouchTarget)
                        .testTag("finance_month_$month").clickable(role = Role.Button,onClickLabel = choose) { onMonth(month) }
                        .semantics(mergeDescendants = true) { contentDescription = description; selected = active },
                        horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Equal plot boxes share a baseline. The narrow bar is inside a >=80dp click target.
                        Layout(content = {
                            Box(Modifier.testTag("finance_bar_$month").clip(RoundedCornerShape(topStart = 4.dp,topEnd = 4.dp)).background(color))
                        },modifier = Modifier.fillMaxWidth().height(plotHeight).testTag("finance_plot_$month")) { children,constraints ->
                            val barWidth = minOf(32.dp.roundToPx(),constraints.maxWidth)
                            val height = chartHeightPx(data.totalMinor,maximum,constraints.maxHeight)
                            val bar = children.single().measure(Constraints.fixed(barWidth,height))
                            layout(constraints.maxWidth,constraints.maxHeight) {
                                bar.placeRelative((constraints.maxWidth - barWidth) / 2,constraints.maxHeight - height)
                            }
                        }
                        Text(month.format(shortMonth),style = MaterialTheme.typography.labelLarge,color = color)
                        Text(month.year.toString(),style = MaterialTheme.typography.labelMedium,color = TrackerDesign.colors.textSecondary)
                        Box(Modifier.height(24.dp),contentAlignment = Alignment.Center) {
                            if(active) Icon(Icons.Default.Check,null,Modifier.size(20.dp),tint = TrackerDesign.colors.textPrimary)
                        }
                    }
                }
            }
        }
    }
}
