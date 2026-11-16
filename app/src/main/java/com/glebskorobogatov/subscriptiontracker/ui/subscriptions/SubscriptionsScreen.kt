package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.util.UUID

@Composable
fun SubscriptionsScreen(state: SubscriptionsUiState, onAdd: () -> Unit, onOpen: (UUID) -> Unit, onRetry: () -> Unit) {
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))) {
        LazyColumn(Modifier.fillMaxSize().testTag("subscriptions_screen"),
            contentPadding = PaddingValues(horizontal = TrackerDimensions.screenPadding, vertical = 16.dp)) {
            when (state) {
                SubscriptionsUiState.Loading -> item(key = "loading") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.subscriptions_loading))
                    }
                }
                SubscriptionsUiState.Error -> item(key = "error") { LoadError(onRetry) }
                is SubscriptionsUiState.Empty -> item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(48.dp), tint = TrackerDesign.colors.textSecondary)
                        Text(stringResource(R.string.subscriptions_empty), style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { heading() })
                        Text(stringResource(if (state.names.isEmpty()) R.string.subscriptions_empty_explanation else R.string.subscriptions_only_archive),
                            color = TrackerDesign.colors.textSecondary, textAlign = TextAlign.Center)
                        TrackerPrimaryButton(stringResource(R.string.add_first_subscription), onAdd, Modifier.fillMaxWidth())
                    }
                }
                is SubscriptionsUiState.Content -> {
                    item(key = "forecast") { ForecastSummary(state.forecast) }
                    if (state.needsDecision.isNotEmpty()) {
                        item(key = "decisions") {
                            SectionTitle(stringResource(R.string.needs_decision),
                                Modifier.padding(top = TrackerDimensions.sectionSpacing).testTag("needs_decision_heading"))
                        }
                        itemsIndexed(state.needsDecision, key = { _, row -> row.id.toString() }) { index, row ->
                            SubscriptionRow(row, onOpen)
                            if (index < state.needsDecision.lastIndex) SubscriptionDivider(row.id)
                        }
                    }
                    if (state.active.isNotEmpty()) {
                        item(key = "active") {
                            SectionTitle(stringResource(R.string.active_subscriptions),
                                Modifier.padding(top = TrackerDimensions.sectionSpacing).testTag("active_subscriptions_heading"))
                        }
                        itemsIndexed(state.active, key = { _, row -> row.id.toString() }) { index, row ->
                            SubscriptionRow(row, onOpen)
                            if (index < state.active.lastIndex) SubscriptionDivider(row.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SubscriptionDivider(id: UUID) {
    HorizontalDivider(color = TrackerDesign.colors.divider, thickness = 1.dp,
        modifier = Modifier.testTag("subscription_divider_$id"))
}

@Composable internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
}

@Composable internal fun LoadError(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle(stringResource(R.string.subscriptions_error))
        Text(stringResource(R.string.subscriptions_error_explanation), color = TrackerDesign.colors.textSecondary)
        TrackerPrimaryButton(stringResource(R.string.retry_loading), onRetry)
    }
}
