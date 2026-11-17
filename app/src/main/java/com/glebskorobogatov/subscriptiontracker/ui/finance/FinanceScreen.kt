package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun FinanceScreen(model: FinanceViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    FinancePane(state,model::selectMonth,model::retry)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FinancePane(state: FinanceUiState,onMonth: (YearMonth) -> Unit = {},onRetry: () -> Unit = {}) {
    val snapshot = state.snapshot
    val forecast = snapshot?.forecast
    val monthFormat = remember { DateTimeFormatter.ofPattern("LLLL yyyy",Locale.forLanguageTag("ru")) }
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(lineHeightStyle =
        LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None))) {
        LazyColumn(Modifier.fillMaxSize().testTag("finance_screen"),
            contentPadding = PaddingValues(horizontal = TrackerDimensions.screenPadding,vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.finance_explanation),color = TrackerDesign.colors.textSecondary) }
            when(state.phase) {
                FinancePhase.Loading -> item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(); Text(stringResource(R.string.finance_loading))
                } }
                FinancePhase.Error -> item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.finance_error),color = TrackerDesign.colors.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TrackerSecondaryButton(stringResource(R.string.retry_loading),onRetry)
                } }
                else -> {
                    if(state.phase == FinancePhase.Empty) item {
                        Text(stringResource(R.string.finance_empty),Modifier.testTag("finance_empty"),style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.finance_empty_explanation),color = TrackerDesign.colors.textSecondary)
                    }
                    if(forecast != null) {
                        item {
                            TrackerSummaryCard(Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.forecast_30_days),style = MaterialTheme.typography.titleSmall)
                                FinanceMoney(Money(forecast.next30Days.totalMinor,CurrencyCode.of("RUB")),Modifier.testTag("finance_30_total"))
                                Text(stringResource(R.string.finance_expected_total),style = MaterialTheme.typography.titleSmall)
                                FinanceMoney(Money(forecast.totalMinor,CurrencyCode.of("RUB")),Modifier.testTag("finance_total"))
                                Text(stringResource(R.string.finance_window,forecast.fromInclusive.toString(),forecast.untilExclusive.minusDays(1).toString()),
                                    style = MaterialTheme.typography.bodySmall,color = TrackerDesign.colors.textSecondary)
                            }
                        }
                        item { Text(stringResource(R.string.finance_months),style = MaterialTheme.typography.titleMedium,modifier = Modifier.semantics { heading() }) }
                        item(key = "monthly_chart") {
                            MonthlyForecastChart(forecast.months,state.month,onMonth)
                        }
                        item {
                            Text(stringResource(R.string.finance_month_details,state.month?.format(monthFormat).orEmpty()),
                                style = MaterialTheme.typography.titleMedium,modifier = Modifier.testTag("finance_details_heading").semantics { heading() })
                            FinanceMoney(Money(forecast.months[state.month]?.totalMinor ?: 0L,CurrencyCode.of("RUB")),
                                Modifier.testTag("finance_selected_total"))
                        }
                        val charges = forecast.months[state.month]?.charges.orEmpty()
                        if(charges.isEmpty()) item { Text(stringResource(R.string.finance_month_empty),Modifier.testTag("finance_month_empty"),color = TrackerDesign.colors.textSecondary) }
                        items(charges,key = { "charge_${it.subscriptionId}_${it.dueDate}" }) { charge ->
                            val row = snapshot.rows.firstOrNull { it.id == charge.subscriptionId.value }
                            Column(Modifier.fillMaxWidth().testTag("finance_charge_${charge.subscriptionId}_${charge.dueDate}").padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row?.name ?: stringResource(R.string.finance_missing_name),style = MaterialTheme.typography.titleSmall)
                                row?.accountLabel?.takeIf(String::isNotBlank)?.let { Text(it,color = TrackerDesign.colors.textSecondary) }
                                FinanceMoney(Money(charge.amountMinor,CurrencyCode.of("RUB")))
                                Text(stringResource(R.string.finance_expected_date,charge.dueDate.toString()))

                            }
                            HorizontalDivider(color = TrackerDesign.colors.divider)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun FinanceMoney(money: Money,modifier: Modifier = Modifier) {
    Text(stringResource(R.string.money_amount,money.format(),money.currency.value),modifier,
        style = MaterialTheme.typography.titleMedium)
}
