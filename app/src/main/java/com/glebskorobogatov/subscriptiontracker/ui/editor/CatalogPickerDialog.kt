package com.glebskorobogatov.subscriptiontracker.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.catalog.OfflineIconCatalog
import com.glebskorobogatov.subscriptiontracker.ui.design.SubscriptionThemeIcon

@Composable
internal fun CatalogPickerDialog(selectedId: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit,
    onPickImage: () -> Unit = {}) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.editor_catalog_title), style = MaterialTheme.typography.titleLarge)
                LazyVerticalGrid(GridCells.Adaptive(minSize = 88.dp), Modifier.weight(1f, fill = false).testTag("catalog_grid"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(OfflineIconCatalog.entries, key = { it.id }) { entry ->
                        val name = stringResource(entry.title)
                        Column(Modifier.fillMaxWidth().heightIn(min = 96.dp)
                            .semantics { selected = entry.id == selectedId }
                            .clickable(role = Role.Button) { onSelect(entry.id) }
                            .testTag("catalog_${entry.id}"),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SubscriptionThemeIcon(entry.id, 48.dp)
                            Text(name, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                TextButton(onClick = onPickImage, modifier = Modifier.align(Alignment.CenterHorizontally)
                    .testTag("catalog_pick_image")) { Text(stringResource(R.string.editor_pick_own_image)) }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.editor_catalog_close))
                }
            }
        }
    }
}
