package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState
import kotlin.math.roundToInt

internal enum class PeriodTone { HIDDEN, NEUTRAL, SOON, CANCELLED }

/** Style only: consume the domain projection, never calculate a period/date in Compose.
 * Keep Double until drawing. Converting 0.2 to Float before comparing can misclassify a value >20%. */
internal fun periodTone(fraction: Double?, days: Long?, state: SubscriptionState): PeriodTone = when {
    fraction == null || !fraction.isFinite() || fraction !in 0.0..1.0 || state == SubscriptionState.ARCHIVED -> PeriodTone.HIDDEN
    state == SubscriptionState.CANCELLED -> PeriodTone.CANCELLED
    fraction <= 0.2 && days != null && days in 0L..3L -> PeriodTone.SOON
    else -> PeriodTone.NEUTRAL
}

@Composable
fun TrackerPeriodIndicator(fraction: Double?, days: Long?, state: SubscriptionState,
    description: String, modifier: Modifier = Modifier, indicatorModifier: Modifier = Modifier) {
    val tone = periodTone(fraction,days,state)
    if (tone == PeriodTone.HIDDEN) return
    val value = requireNotNull(fraction)
    val colors = TrackerDesign.colors
    val fill = if (tone == PeriodTone.SOON) colors.statusSoon else colors.textSecondary
    val label = stringResource(R.string.tracker_period_remaining,(value*100).roundToInt())
    Box(modifier.fillMaxWidth().height(TrackerDimensions.progressHeight)) {
        Canvas(Modifier.fillMaxSize().then(indicatorModifier).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(),0f..1f)
            stateDescription = "$description. $label"
        }) {
            val radius = CornerRadius(size.height/2)
            drawRoundRect(colors.divider,cornerRadius = radius)
            if (value > 0.0) drawRoundRect(fill,size = Size((size.width*value).toFloat(),size.height),cornerRadius = radius)
        }
    }
}

/** Short feedback supplied only by a caller which has received a successful commit.
 * No timers, navigation, mutation or speculative success is owned by this component. */
@Composable
fun TrackerSuccessCapsule(message: String, modifier: Modifier = Modifier) {
    Surface(modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        color = TrackerDesign.colors.surfaceRaised, shape = TrackerShapes.capsule, tonalElevation = 0.dp,shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 20.dp,vertical = 12.dp),verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Check,null,tint = TrackerDesign.colors.statusCalm)
            Text(message,color = TrackerDesign.colors.textPrimary)
        }
    }
}

@Composable
fun TrackerLoading(message: String, modifier: Modifier = Modifier) {
    Column(modifier,verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
        CircularProgressIndicator(color = TrackerDesign.colors.textSecondary)
        Text(message)
    }
}

@Composable
fun TrackerEmpty(title: String, message: String, modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null) {
    Column(modifier,verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
        Text(title,style = MaterialTheme.typography.titleLarge,modifier = Modifier.semantics { heading() })
        Text(message,color = TrackerDesign.colors.textSecondary)
        action?.invoke()
    }
}

/** Caller chooses the specific forecast/calendar/storage copy and retry; these states never merge. */
@Composable
fun TrackerError(message: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    TrackerSummaryCard(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Warning,null,tint = TrackerDesign.colors.error)
            Text(message,Modifier.weight(1f).semantics { error(message); liveRegion = LiveRegionMode.Polite },
                color = TrackerDesign.colors.textPrimary)
        }
        TrackerTextButton(retryLabel,onRetry)
    }
}

@Composable
fun TrackerStatusLabel(text: String, modifier: Modifier = Modifier, secondary: Boolean = false,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
    Text(text,modifier,style = style,color = if(secondary) TrackerDesign.colors.textSecondary else TrackerDesign.colors.textPrimary)
}
