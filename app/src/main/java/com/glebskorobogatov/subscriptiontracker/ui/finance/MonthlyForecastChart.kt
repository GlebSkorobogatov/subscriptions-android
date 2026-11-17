package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.first

/** One selection owned by E-01. This component owns only transient scroll/pixel geometry. */
@Composable internal fun MonthlyForecastChart(months: Map<YearMonth,MonthlyForecast>,
    selectedMonth: YearMonth?,onMonth: (YearMonth) -> Unit) {
    val entries = months.entries.toList()
    val maximum = entries.maxOfOrNull { it.value.totalMinor } ?: 0L
    val fullMonth = remember { DateTimeFormatter.ofPattern("LLLL yyyy",Locale.forLanguageTag("ru")) }
    val shortMonth = remember { DateTimeFormatter.ofPattern("LLL",Locale.forLanguageTag("ru")) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val plotHeight = if(windowHeight > 0.dp && windowHeight < 480.dp) 96.dp else 128.dp
    var viewportPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val visibleCount = minOf(entries.size.coerceAtLeast(1),if(maxWidth >= 600.dp && density.fontScale < 1.5f) 6 else 3)
        val gap = TrackerDimensions.itemSpacing
        val width = maxOf(80.dp * density.fontScale.coerceAtLeast(1f),(maxWidth - gap * (visibleCount - 1)) / visibleCount)
        val widthPx = with(density) { width.roundToPx() }
        val gapPx = with(density) { gap.roundToPx() }
        LaunchedEffect(selectedMonth,months.keys.toList(),widthPx,viewportPx,scroll.maxValue) {
            val index = entries.indexOfFirst { it.key == selectedMonth }
            if(index >= 0 && viewportPx > 0) {
                snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
                val left = index * (widthPx + gapPx)
                val right = left + widthPx
                val target = when {
                    left < scroll.value -> left
                    right > scroll.value + viewportPx -> right - viewportPx
                    else -> scroll.value
                }
                scroll.scrollTo(target.coerceIn(0,scroll.maxValue))
            }
        }
        Row(Modifier.fillMaxWidth().onSizeChanged { viewportPx = it.width }
            .horizontalScroll(scroll).testTag("finance_month_chart").semantics { isTraversalGroup = true },
            horizontalArrangement = Arrangement.spacedBy(gap)) {
            entries.forEach { (month,data) ->
                val money = Money(data.totalMinor,CurrencyCode.of("RUB"))
                val active = month == selectedMonth
                val description = stringResource(R.string.finance_chart_column,month.format(fullMonth),money.format(),"RUB")
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
                        val height = chartHeightPx(money.amountMinor,maximum,constraints.maxHeight)
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
