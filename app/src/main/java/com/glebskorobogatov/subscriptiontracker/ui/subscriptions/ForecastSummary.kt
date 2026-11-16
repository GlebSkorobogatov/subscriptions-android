package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.domain.model.Money
import com.glebskorobogatov.subscriptiontracker.ui.design.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ForecastSummary(totals: List<Money>) {
    TrackerSummaryCard(Modifier.fillMaxWidth().testTag("forecast_summary").semantics(mergeDescendants = true) {}) {
        SectionTitle(stringResource(R.string.forecast_30_days))
        Text(stringResource(R.string.forecast_explanation), style = MaterialTheme.typography.bodySmall,
            color = TrackerDesign.colors.textSecondary)
        if (totals.isEmpty()) Text(stringResource(R.string.forecast_empty))
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            totals.forEach { money ->
                val value = buildAnnotatedString {
                    append(money.format())
                    withStyle(MaterialTheme.typography.bodyMedium.toSpanStyle()) { append(" "); append(money.currency.value) }
                }
                Text(value, style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.testTag("forecast_${money.currency.value}"))
            }
        }
    }
}
