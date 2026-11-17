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
internal fun ForecastSummary(totalMinor: Long?, onRetry: () -> Unit = {}) {
    TrackerSummaryCard(Modifier.fillMaxWidth().testTag("forecast_summary").semantics(mergeDescendants = true) {}) {
        SectionTitle(stringResource(R.string.forecast_30_days))
        Text(stringResource(R.string.forecast_explanation), style = MaterialTheme.typography.bodySmall,
            color = TrackerDesign.colors.textSecondary)
        if (totalMinor == null) {
            Text(stringResource(R.string.finance_error),modifier = Modifier.testTag("forecast_error"),color = TrackerDesign.colors.error)
            TrackerSecondaryButton(stringResource(R.string.retry_loading),onRetry)
            return@TrackerSummaryCard
        }
        val money = Money(totalMinor, com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode.of("RUB"))
        val value = buildAnnotatedString {
            append(money.format())
            withStyle(MaterialTheme.typography.bodyMedium.toSpanStyle()) { append(" RUB") }
        }
        Text(value, style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("forecast_RUB"))
        if (totalMinor == 0L) Text(stringResource(R.string.forecast_empty))
    }
}
