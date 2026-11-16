package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Composable
internal fun SubscriptionRow(model: SubscriptionCardModel, onOpen: (UUID) -> Unit) {
    val openLabel = stringResource(R.string.open_subscription_details)
    Row(Modifier.fillMaxWidth().heightIn(min = TrackerDimensions.minimumTouchTarget)
        .testTag("subscription_${model.id}").clickable(role = Role.Button, onClickLabel = openLabel) { onOpen(model.id) }
        .padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        val logoEdge = maxOf(TrackerDimensions.logoSize,
            with(LocalDensity.current) { MaterialTheme.typography.titleLarge.lineHeight.toDp() } + 16.dp)
        val name = model.name.trimStart()
        val initial = if (name.isEmpty()) "?" else name.substring(0, name.offsetByCodePoints(0, 1)).uppercase(Locale.ROOT)
        com.glebskorobogatov.subscriptiontracker.ui.design.SubscriptionImage(model.name, model.logoAssetId, logoEdge,
            catalogId = model.catalogLogoId)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val priceWidth = maxWidth / 2
                val stackPrice = maxWidth < 240.dp || LocalDensity.current.fontScale >= 1.5f
                if (stackPrice) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Identity(model)
                    Price(model)
                } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { Identity(model) }
                    Column(Modifier.widthIn(max = priceWidth)) { Price(model, rightAligned = true) }
                }
            }
            val status = when (model.status) {
                VisibleStatus.ACTIVE -> if (model.mode == RenewalMode.AUTO) R.string.status_auto else R.string.status_manual
                VisibleStatus.AWAITING_RENEWAL -> R.string.status_awaiting
                VisibleStatus.PAYMENT_ISSUE -> R.string.status_payment_issue
                VisibleStatus.CANCELLATION_SCHEDULED -> R.string.status_cancelled
                VisibleStatus.ARCHIVED -> R.string.status_archived
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (model.status == VisibleStatus.PAYMENT_ISSUE || model.status == VisibleStatus.AWAITING_RENEWAL) {
                    Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = TrackerDesign.colors.statusUrgent)
                }
                Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
            }
            model.eventDate?.let { date ->
                Text(stringResource(if (model.status == VisibleStatus.CANCELLATION_SCHEDULED) R.string.access_until else R.string.expected_on,
                    date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))),
                    style = MaterialTheme.typography.bodySmall, color = TrackerDesign.colors.textSecondary)
                when {
                    model.remainingDays == 0L -> Text(stringResource(R.string.event_today), style = MaterialTheme.typography.bodySmall)
                    model.remainingDays != null && model.remainingDays > 0 -> Text(stringResource(R.string.days_remaining, model.remainingDays),
                        style = MaterialTheme.typography.bodySmall, color = TrackerDesign.colors.textSecondary)
                }
            }
            val explanation = when (model.status) {
                VisibleStatus.AWAITING_RENEWAL -> R.string.awaiting_explanation
                VisibleStatus.PAYMENT_ISSUE -> R.string.payment_issue_explanation
                VisibleStatus.CANCELLATION_SCHEDULED -> if (model.eventDate == null) R.string.access_unknown else null
                else -> if (model.eventDate == null) R.string.event_unknown else null
            }
            explanation?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = TrackerDesign.colors.textSecondary) }
            // Display the supplied domain fraction; awaiting renewal has an explicitly empty track.
            val progress = when (model.status) {
                VisibleStatus.AWAITING_RENEWAL -> 0f
                VisibleStatus.PAYMENT_ISSUE, VisibleStatus.ARCHIVED -> null
                else -> model.remainingFraction
            }
            progress?.let {
                val progressText = stringResource(R.string.progress_status, stringResource(status))
                val color = when {
                    model.status == VisibleStatus.AWAITING_RENEWAL || (model.remainingDays != null && model.remainingDays <= 1) -> TrackerDesign.colors.statusUrgent
                    model.remainingDays != null && model.remainingDays <= 3 -> TrackerDesign.colors.statusSoon
                    else -> TrackerDesign.colors.statusCalm
                }
                // The visible track is 4 dp; Material retains its larger accessibility semantics bounds.
                Box(Modifier.fillMaxWidth().height(TrackerDimensions.progressHeight)
                    .testTag("subscription_progress_track_${model.id}")) {
                    LinearProgressIndicator(progress = { it }, color = color, trackColor = TrackerDesign.colors.divider,
                        strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                            .testTag("subscription_progress_${model.id}").semantics { stateDescription = progressText })
                }
            }
        }
    }
}

@Composable private fun Identity(model: SubscriptionCardModel) {
    Text(model.name, style = MaterialTheme.typography.titleSmall)
    model.accountLabel?.takeIf(String::isNotBlank)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = TrackerDesign.colors.textSecondary)
    }
}

@Composable private fun Price(model: SubscriptionCardModel, rightAligned: Boolean = false) {
    val alignment = if (rightAligned) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Text(stringResource(R.string.money_amount, model.price.format(), model.price.currency.value), style = MaterialTheme.typography.titleSmall)
        val frequency = when (model.frequency.unit) {
            FrequencyUnit.DAY -> R.string.frequency_days; FrequencyUnit.WEEK -> R.string.frequency_weeks
            FrequencyUnit.MONTH -> R.string.frequency_months; FrequencyUnit.YEAR -> R.string.frequency_years
        }
        Text(stringResource(frequency, model.frequency.count), style = MaterialTheme.typography.bodySmall, color = TrackerDesign.colors.textSecondary)
    }
}
