package com.glebskorobogatov.subscriptiontracker.ui.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import com.glebskorobogatov.subscriptiontracker.ui.details.periodText
import java.util.UUID

@Composable
fun ArchiveScreen(vm: ArchiveViewModel, onOpen: (UUID) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    ArchivePane(state, onOpen, vm::retry)
}

/** Classification, stable sorting and resource ownership remain in the existing ViewModel. */
@Composable
internal fun ArchivePane(state: ArchiveUiState, onOpen: (UUID) -> Unit, onRetry: () -> Unit) {
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(lineHeightStyle =
        LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))) {
        LazyColumn(Modifier.fillMaxSize().testTag("archive_screen"),
            contentPadding = PaddingValues(horizontal = trackerPageHorizontalPadding(), vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.phase) {
                ArchivePhase.Loading -> item { Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(); Text(stringResource(R.string.archive_loading))
                } }
                ArchivePhase.Error -> item { Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.archive_error), color = TrackerDesign.colors.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TrackerSecondaryButton(stringResource(R.string.retry_loading), onRetry)
                } }
                ArchivePhase.Empty -> item { Column(Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_archive), null, Modifier.size(40.dp), tint = TrackerDesign.colors.textSecondary)
                    Text(stringResource(R.string.archive_empty), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.archive_empty_explanation), color = TrackerDesign.colors.textSecondary, textAlign = TextAlign.Center)
                } }
                ArchivePhase.Content -> items(state.rows, key = { it.id.toString() }) { row -> ArchiveRow(row, onOpen) }
            }
        }
    }
}

@Composable private fun ArchiveRow(row: SubscriptionEntity, onOpen: (UUID) -> Unit) {
    val label = stringResource(R.string.open_subscription_details)
    Row(Modifier.fillMaxWidth().heightIn(min = TrackerDimensions.minimumTouchTarget).testTag("archive_${row.id}")
        .clickable(role = Role.Button, onClickLabel = label) { onOpen(row.id) }.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        SubscriptionImage(row.name, row.logoAssetId, catalogId = row.catalogLogoId)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val priceWidth = maxWidth * 0.45f
                val measurer = rememberTextMeasurer()
                val priceStyle = MaterialTheme.typography.titleSmall
                val priceText = formatTrackerRub(row.priceMinor)
                val pricePx = measurer.measure(priceText, priceStyle, constraints = Constraints(maxWidth = Int.MAX_VALUE)).size.width
                val sidePx = with(LocalDensity.current) { priceWidth.toPx() }
                val stacked = maxWidth < 240.dp || LocalDensity.current.fontScale >= 1.5f || pricePx > sidePx
                @Composable fun identity() {
                    Text(row.name, style = MaterialTheme.typography.titleSmall, color = TrackerDesign.colors.textPrimary)
                    row.accountLabel?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = TrackerDesign.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                @Composable fun price() {
                    TrackerMoney(row.priceMinor, Modifier.fillMaxWidth(), priceStyle.copy(textAlign = if (stacked) TextAlign.Start else TextAlign.End))
                    Text(periodText(row.intervalUnit, row.intervalCount), Modifier.fillMaxWidth(),
                        color = TrackerDesign.colors.textSecondary, style = MaterialTheme.typography.bodySmall,
                        textAlign = if (stacked) TextAlign.Start else TextAlign.End)
                }
                if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { identity(); price() }
                else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) { identity() }
                    Column(Modifier.widthIn(max = priceWidth)) { price() }
                }
            }
            val dated = row.cancelledAccessEndExclusive?.let { stringResource(R.string.access_until, it.toString()) }
                ?: row.archivedAt?.let { stringResource(R.string.archived_on, it.toString()) }
            dated?.let { Text(it, color = TrackerDesign.colors.textSecondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
