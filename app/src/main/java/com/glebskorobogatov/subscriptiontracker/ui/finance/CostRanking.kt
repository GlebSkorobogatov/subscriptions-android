package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.settings.CostRankingPeriod
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ExactKopecks
import com.glebskorobogatov.subscriptiontracker.domain.forecast.NormalizedSubscriptionCost
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.math.BigDecimal
import java.math.MathContext

/** Convert only the bounded ratio to a layout fraction, even for very large rational terms. */
internal fun rankingBarFraction(amount: ExactKopecks, largest: ExactKopecks): Float {
    if (largest.numerator.signum() == 0) return 0f
    val numerator = amount.numerator * largest.denominator
    val denominator = largest.numerator * amount.denominator
    return BigDecimal(numerator).divide(BigDecimal(denominator), MathContext.DECIMAL64).toFloat()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CostRankingHeading(
    period: CostRankingPeriod,
    onPeriod: (CostRankingPeriod) -> Unit,
    enabled: Boolean,
    writeError: Boolean,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FinanceHeading(stringResource(R.string.finance_ranking_title), Modifier.testTag("finance_ranking_heading"))
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CostRankingPeriod.entries.forEach { option ->
                val label = if (option == CostRankingPeriod.MONTH) R.string.finance_ranking_month else R.string.finance_ranking_year
                TrackerSelectedCapsule(stringResource(label), option == period, { onPeriod(option) },
                    Modifier.testTag("finance_ranking_period_${option.name}"), enabled = enabled)
            }
        }
        if (writeError) {
            TrackerError(
                stringResource(R.string.finance_ranking_write_error),
                stringResource(R.string.settings_retry_write),
                onRetry,
                Modifier.testTag("finance_ranking_write_error"),
            )
        }
    }
}

@Composable
internal fun CostRankingRow(
    entry: NormalizedSubscriptionCost,
    largestAnnual: ExactKopecks,
    row: SubscriptionEntity?,
    period: CostRankingPeriod,
) {
    val amount = entry.amounts
    // Monthly and annual ratios are identical: both terms would be divided by 12.
    val fraction = remember(amount.exactAnnual, largestAnnual) { rankingBarFraction(amount.exactAnnual, largestAnnual) }
    Column(
        Modifier.fillMaxWidth().testTag("finance_ranking_${entry.subscriptionId}").padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FinanceValueLine(
            if (period == CostRankingPeriod.MONTH) amount.monthlyMinor else amount.annualMinor,
            amountModifier = Modifier.testTag("finance_ranking_amount_${entry.subscriptionId}"),
        ) {
            Text(row?.name ?: stringResource(R.string.finance_missing_name), style = MaterialTheme.typography.titleSmall)
            row?.accountLabel?.takeIf(String::isNotBlank)?.let { Text(it, color = TrackerDesign.colors.textSecondary) }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(TrackerShapes.capsule).background(TrackerDesign.colors.surfaceSubtle)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(TrackerShapes.capsule)
                .background(TrackerDesign.colors.textSecondary).testTag("finance_ranking_bar_${entry.subscriptionId}"))
        }
    }
}
