package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState
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
                SubscriptionState.ACTIVE -> R.string.details_active
                SubscriptionState.CANCELLED -> R.string.status_cancelled
                SubscriptionState.ARCHIVED -> R.string.status_archived
            }
            TrackerStatusLabel(stringResource(status),secondary = model.status == SubscriptionState.CANCELLED)
            model.eventDate?.let { date ->
                Text(stringResource(if (model.status == SubscriptionState.CANCELLED) R.string.access_until else R.string.expected_on,
                    date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))),
                    style = MaterialTheme.typography.bodySmall, color = TrackerDesign.colors.textSecondary)
                when {
                    model.remainingDays == 0L -> Text(stringResource(R.string.event_today), style = MaterialTheme.typography.bodySmall)
                    model.remainingDays != null && model.remainingDays > 0 -> Text(stringResource(R.string.days_remaining, model.remainingDays),
                        style = MaterialTheme.typography.bodySmall, color = TrackerDesign.colors.textSecondary)
                }
            }
            if (model.eventDate == null) Text(stringResource(R.string.event_unknown), style = MaterialTheme.typography.bodySmall)
            if (model.calendarError) Text(stringResource(R.string.projection_calendar_error),
                modifier = Modifier.testTag("calendar_error_${model.id}"),color = TrackerDesign.colors.error)
            TrackerPeriodIndicator(model.remainingFraction,model.remainingDays,model.status,
                stringResource(R.string.progress_status,stringResource(status)),
                modifier = Modifier.testTag("subscription_progress_track_${model.id}"),
                indicatorModifier = Modifier.testTag("subscription_progress_${model.id}"))
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
