package com.glebskorobogatov.subscriptiontracker.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.catalog.OfflineIconCatalog
import com.glebskorobogatov.subscriptiontracker.ui.design.SubscriptionThemeIcon
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerBottomSheet
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerDesign
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerDimensions
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerSecondaryButton
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerShapes

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CatalogPickerDialog(selectedId: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit,
    onPickImage: () -> Unit = {}) {
    TrackerBottomSheet(onDismiss, Modifier.testTag("catalog_sheet"), skipPartiallyExpanded = true, footer = {
        TrackerSecondaryButton(stringResource(R.string.editor_pick_own_image), onPickImage,
            Modifier.fillMaxWidth().testTag("catalog_pick_image"))
        TrackerSecondaryButton(stringResource(R.string.editor_catalog_close), onDismiss, Modifier.fillMaxWidth())
    }) {
        Text(stringResource(R.string.editor_catalog_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = (maxWidth.value / 88f).toInt().coerceAtLeast(1)
            val itemWidth = maxWidth / columns
            val selectedLabel = stringResource(R.string.tracker_selected)
            FlowRow(Modifier.fillMaxWidth().testTag("catalog_grid").selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OfflineIconCatalog.entries.forEach { entry ->
                    val name = stringResource(entry.title)
                    val selected = entry.id == selectedId
                    Column(Modifier.width(itemWidth).heightIn(min = 96.dp)
                        .clip(TrackerShapes.field)
                        .background(if (selected) TrackerDesign.colors.selectedTabBg else Color.Transparent)
                        .testTag("catalog_${entry.id}")
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(entry.id) })
                        .semantics { if (selected) stateDescription = selectedLabel }
                        .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.TopEnd) {
                            SubscriptionThemeIcon(entry.id, 48.dp)
                            if (selected) Box(Modifier.size(18.dp).align(Alignment.TopEnd)
                                .background(TrackerDesign.colors.primaryButtonBg, CircleShape)
                                .semantics(mergeDescendants = true) { testTag = "catalog_selected_mark" },
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Check, null, Modifier.size(12.dp),
                                    tint = TrackerDesign.colors.primaryButtonText)
                            }
                        }
                        Text(name, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
