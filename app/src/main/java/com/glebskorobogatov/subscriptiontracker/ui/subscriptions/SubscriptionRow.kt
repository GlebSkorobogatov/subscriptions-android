package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState
import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Composable internal fun SubscriptionRow(model: SubscriptionCardModel,onOpen: (UUID) -> Unit) {
    val cancelled = model.status == SubscriptionState.CANCELLED
    val foreground = if(cancelled) TrackerDesign.colors.textSecondary else TrackerDesign.colors.textPrimary
    val activeStatus = stringResource(R.string.details_active)
    val statusWithoutIndicator = !cancelled && periodTone(model.remainingFraction,model.remainingDays,model.status) == PeriodTone.HIDDEN
    Row(Modifier.fillMaxWidth().heightIn(min = TrackerDimensions.minimumTouchTarget).testTag("subscription_${model.id}")
        .clickable(role = Role.Button,onClickLabel = stringResource(R.string.open_subscription_details)) { onOpen(model.id) }
        .padding(vertical = 16.dp),horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.Top) {
        Box(Modifier.size(TrackerDimensions.logoSize).testTag("subscription_icon_${model.id}")) {
            SubscriptionImage(model.name,model.logoAssetId,catalogId = model.catalogLogoId)
        }
        CompositionLocalProvider(LocalContentColor provides foreground) {
            Column(Modifier.weight(1f).testTag("subscription_text_${model.id}").then(
                if(statusWithoutIndicator) Modifier.semantics { stateDescription = activeStatus } else Modifier),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val priceWidth = maxWidth*0.45f
                    val measurer = rememberTextMeasurer()
                    val priceStyle = MaterialTheme.typography.titleSmall
                    val periodStyle = MaterialTheme.typography.bodySmall
                    val priceText = formatTrackerRub(model.price.amountMinor)
                    val periodText = periodLabel(model)
                    val pricePx = measurer.measure(priceText,priceStyle,constraints = Constraints(maxWidth = Int.MAX_VALUE)).size.width
                    val periodPx = measurer.measure(periodText,periodStyle,constraints = Constraints(maxWidth = Int.MAX_VALUE)).size.width
                    val needed = maxOf(pricePx,periodPx)
                    val sidePx = with(LocalDensity.current) { priceWidth.toPx() }
                    // A side column that cannot hold the amount wraps the row instead of clipping kopecks.
                    val stacked = maxWidth < 240.dp || LocalDensity.current.fontScale >= 1.5f || needed > sidePx
                    if(stacked) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            IdentityAndDate(model)
                            Column(Modifier.fillMaxWidth(),horizontalAlignment = Alignment.End) { Price(model) }
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) { IdentityAndDate(model) }
                        Column(Modifier.widthIn(max = priceWidth),horizontalAlignment = Alignment.End) { Price(model) }
                    }
                }
                TrackerPeriodIndicator(model.remainingFraction,model.remainingDays,model.status,
                    stringResource(R.string.progress_status,stringResource(if(cancelled) R.string.status_cancelled else R.string.details_active)),
                    Modifier.testTag("subscription_progress_track_${model.id}"),Modifier.testTag("subscription_progress_${model.id}"))
            }
        }
    }
}
@Composable private fun IdentityAndDate(model: SubscriptionCardModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(model.name,Modifier.testTag("subscription_name_${model.id}"),style = MaterialTheme.typography.titleSmall)
        model.accountLabel?.takeIf(String::isNotBlank)?.let { Text(it,style = MaterialTheme.typography.bodySmall,color = TrackerDesign.colors.textSecondary) }
        if(model.status == SubscriptionState.CANCELLED) Surface(shape = TrackerShapes.capsule,color = TrackerDesign.colors.surfaceSubtle) {
            TrackerStatusLabel(stringResource(R.string.status_cancelled),Modifier.padding(horizontal = 8.dp,vertical = 3.dp),secondary = true,
                style = MaterialTheme.typography.labelMedium)
        }
        val date = model.eventDate
        val days = model.remainingDays
        val dateText = when {
            date == null -> stringResource(if(model.status == SubscriptionState.CANCELLED) R.string.access_unknown else R.string.event_unknown)
            model.status == SubscriptionState.CANCELLED -> stringResource(R.string.access_until,date.format(DateTimeFormatter.ofPattern("d MMM",Locale.forLanguageTag("ru"))))
            days == 0L -> stringResource(R.string.event_today)
            days != null && days > 0 -> stringResource(R.string.home_date_days,date.format(DateTimeFormatter.ofPattern("d MMM",Locale.forLanguageTag("ru"))),days)
            else -> date.format(DateTimeFormatter.ofPattern("d MMM",Locale.forLanguageTag("ru")))
        }
        Text(dateText,Modifier.testTag("subscription_date_${model.id}"),style = MaterialTheme.typography.bodySmall,color = TrackerDesign.colors.textSecondary)
        if(model.status == SubscriptionState.CANCELLED && days != null) {
            if(days == 0L) Text(stringResource(R.string.event_today),style = MaterialTheme.typography.bodySmall)
            else if(days > 0) Text(stringResource(R.string.days_remaining,days),style = MaterialTheme.typography.bodySmall,color = TrackerDesign.colors.textSecondary)
        }
        if(model.calendarError) Text(stringResource(R.string.projection_calendar_error),Modifier.testTag("calendar_error_${model.id}"),color = TrackerDesign.colors.error)
    }
}
@Composable private fun periodLabel(model: SubscriptionCardModel): String {
    val period = when {
        model.frequency.count == 1 -> when(model.frequency.unit) {
            FrequencyUnit.DAY -> R.string.home_period_day; FrequencyUnit.WEEK -> R.string.home_period_week
            FrequencyUnit.MONTH -> R.string.home_period_month; FrequencyUnit.YEAR -> R.string.home_period_year
        }
        model.frequency.unit == FrequencyUnit.MONTH && model.frequency.count == 3 -> R.string.home_period_quarter
        model.frequency.unit == FrequencyUnit.MONTH && model.frequency.count == 6 -> R.string.home_period_half_year
        else -> when(model.frequency.unit) {
            FrequencyUnit.DAY -> R.string.frequency_days; FrequencyUnit.WEEK -> R.string.frequency_weeks
            FrequencyUnit.MONTH -> R.string.frequency_months; FrequencyUnit.YEAR -> R.string.frequency_years
        }
    }
    return stringResource(period,model.frequency.count)
}
@Composable private fun Price(model: SubscriptionCardModel) {
    TrackerMoney(model.price.amountMinor,Modifier.fillMaxWidth().testTag("subscription_price_${model.id}"),MaterialTheme.typography.titleSmall.copy(textAlign = TextAlign.End))
    Text(periodLabel(model),Modifier.fillMaxWidth(),style = MaterialTheme.typography.bodySmall,color = TrackerDesign.colors.textSecondary,
        textAlign = TextAlign.End)
}
