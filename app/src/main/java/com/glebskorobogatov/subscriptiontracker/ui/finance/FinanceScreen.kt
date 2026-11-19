package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.settings.CostRankingPeriod
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import com.glebskorobogatov.subscriptiontracker.ui.settings.SettingsPhase
import com.glebskorobogatov.subscriptiontracker.ui.settings.SettingsViewModel
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun FinanceScreen(model: FinanceViewModel, settingsModel: SettingsViewModel) {
    val financial by model.state.collectAsStateWithLifecycle()
    val settings by settingsModel.state.collectAsStateWithLifecycle()
    val state = when (settings.phase) {
        SettingsPhase.Loading -> FinanceUiState()
        SettingsPhase.Error -> FinanceUiState(phase = FinancePhase.Error)
        else -> financial
    }
    FinancePane(
        state = state,
        onMonth = model::selectMonth,
        onRetry = {
            if (settings.phase == SettingsPhase.Error) settingsModel.retryRead()
            model.retry()
        },
        period = settings.data?.costRankingPeriod ?: CostRankingPeriod.MONTH,
        onPeriod = settingsModel::setCostRankingPeriod,
        periodEnabled = settings.phase == SettingsPhase.Content && !settings.pending,
        periodWriteError = settings.phase == SettingsPhase.Content && settings.rankingPending,
        onRetryPeriod = settingsModel::retryWrite,
        onReloadPeriod = settingsModel::retryRead,
    )
}

@Composable
internal fun FinancePane(
    state: FinanceUiState,
    onMonth: (YearMonth) -> Unit = {},
    onRetry: () -> Unit = {},
    period: CostRankingPeriod = CostRankingPeriod.MONTH,
    onPeriod: (CostRankingPeriod) -> Unit = {},
    periodEnabled: Boolean = true,
    periodWriteError: Boolean = false,
    onRetryPeriod: () -> Unit = {},
    onReloadPeriod: () -> Unit = {},
) {
    val list = rememberLazyListState()
    val chart = rememberCalendarChartState()
    BindRootList(TrackerTab.Finance, list, canHide = state.phase == FinancePhase.Content)
    val chrome = LocalRootContentPadding.current
    val snapshot = state.snapshot
    val forecast = snapshot?.forecast
    val normalized = snapshot?.normalizedCosts
    val rowsById = remember(snapshot?.rows) { snapshot?.rows.orEmpty().associateBy { it.id } }
    val monthFormat = remember { DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru")) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")) }
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(lineHeightStyle =
        LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .testTag("finance_screen"),
            state = list,
            contentPadding = PaddingValues(
                start = trackerPageHorizontalPadding(), end = trackerPageHorizontalPadding(),
                top = chrome.calculateTopPadding() + 12.dp, bottom = chrome.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.phase) {
                FinancePhase.Loading -> item { TrackerLoading(stringResource(R.string.finance_loading)) }
                FinancePhase.Error -> item {
                    TrackerError(stringResource(R.string.finance_error), stringResource(R.string.retry_loading), onRetry)
                }
                FinancePhase.Empty, FinancePhase.Content -> {
                    if (forecast != null && normalized != null) {
                        item(key = "finance_summary") {
                            TrackerSummaryCard(Modifier.fillMaxWidth().testTag("finance_summary")) {
                                Text(stringResource(R.string.forecast_30_days), style = MaterialTheme.typography.titleSmall)
                                TrackerMoney(forecast.next30Days.totalMinor, Modifier.testTag("finance_30_total"))
                                Text(stringResource(R.string.finance_annual_estimate), style = MaterialTheme.typography.titleSmall)
                                TrackerMoney(normalized.total.annualMinor, Modifier.testTag("finance_total"))
                                Text(stringResource(R.string.finance_explanation), style = MaterialTheme.typography.bodySmall,
                                    color = TrackerDesign.colors.textSecondary)
                            }
                        }
                    }
                    if (state.phase == FinancePhase.Empty) item(key = "active_empty") {
                        FinanceHeading(stringResource(R.string.finance_empty), Modifier.testTag("finance_empty"))
                        Text(stringResource(R.string.finance_empty_explanation), color = TrackerDesign.colors.textSecondary)
                    }
                    if (forecast != null && normalized != null) {
                        item(key = "calendar_heading") {
                            FinanceHeading(stringResource(R.string.finance_months))
                            if (state.phase == FinancePhase.Content && forecast.charges.isEmpty()) {
                                Text(stringResource(R.string.finance_calendar_empty), Modifier.testTag("finance_calendar_empty"),
                                    color = TrackerDesign.colors.textSecondary)
                            }
                        }
                        item(key = "monthly_chart") {
                            MonthlyForecastChart(forecast.months, state.month, chart, onMonth)
                        }
                        val selectedMonth = forecast.months[state.month] ?: forecast.months.values.first()
                        item(key = "month_details_heading") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FinanceHeading(
                                    stringResource(R.string.finance_month_details, selectedMonth.month.format(monthFormat)),
                                    Modifier.testTag("finance_details_heading")
                                )
                                TrackerMoney(selectedMonth.totalMinor, Modifier.testTag("finance_selected_total"),
                                    style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        if (selectedMonth.charges.isEmpty()) item(key = "month_empty") {
                            Text(stringResource(R.string.finance_month_empty), Modifier.testTag("finance_month_empty"),
                                color = TrackerDesign.colors.textSecondary)
                        }
                        items(selectedMonth.charges, key = { "charge_${it.subscriptionId}_${it.dueDate}" }) { charge ->
                            val row = rowsById[charge.subscriptionId.value]
                            FinanceValueLine(
                                charge.amountMinor,
                                modifier = Modifier.testTag("finance_charge_${charge.subscriptionId}_${charge.dueDate}")
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(stringResource(R.string.finance_expected_date, charge.dueDate.format(dateFormat)),
                                    color = TrackerDesign.colors.textSecondary)
                                Text(row?.name ?: stringResource(R.string.finance_missing_name), style = MaterialTheme.typography.titleSmall)
                                row?.accountLabel?.takeIf(String::isNotBlank)?.let {
                                    Text(it, color = TrackerDesign.colors.textSecondary)
                                }
                            }
                            HorizontalDivider(color = TrackerDesign.colors.divider)
                        }
                        item(key = "ranking_heading") {
                            CostRankingHeading(period, onPeriod, periodEnabled, periodWriteError, onRetryPeriod, onReloadPeriod)
                        }
                        val ranking = normalized.ranked
                        val largest = ranking.firstOrNull()?.amounts?.exactAnnual
                        if (largest == null) item(key = "ranking_empty") {
                            Text(stringResource(R.string.finance_ranking_empty), Modifier.testTag("finance_ranking_empty"),
                                color = TrackerDesign.colors.textSecondary)
                        } else items(ranking, key = { "ranking_${it.subscriptionId}" }) { entry ->
                            val row = rowsById[entry.subscriptionId.value]
                            CostRankingRow(entry, largest, row, period)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FinanceHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

/** Align short values at the trailing edge. Long sums and larger fonts get the full row width. */
@Composable
internal fun FinanceValueLine(
    amountMinor: Long,
    modifier: Modifier = Modifier,
    amountModifier: Modifier = Modifier,
    label: @Composable ColumnScope.() -> Unit,
) {
    val measure = rememberTextMeasurer()
    val amountStyle = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
    val density = LocalDensity.current
    val amountWidth = with(density) {
        measure.measure(formatTrackerRub(amountMinor), style = amountStyle, softWrap = false).size.width.toDp()
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < 240.dp || density.fontScale >= 1.5f || amountWidth > maxWidth * 0.45f) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                label()
                TrackerMoney(amountMinor, amountModifier.align(Alignment.End), style = amountStyle)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = label)
                TrackerMoney(amountMinor, amountModifier, style = amountStyle)
            }
        }
    }
}
