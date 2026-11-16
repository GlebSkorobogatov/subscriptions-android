package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.ui.design.*

/** Natural height keeps the title and both actions available when text wraps at large font scales. */
@Composable
fun SubscriptionsTopBar(onAdd: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
        .padding(horizontal = TrackerDimensions.screenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.subscriptions), Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.headlineLarge.copy(
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(shape = CircleShape, color = TrackerDesign.colors.surfaceSubtle) {
                IconButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, stringResource(R.string.add_subscription), tint = TrackerDesign.colors.textSecondary)
                }
            }
            Surface(shape = CircleShape, color = TrackerDesign.colors.surfaceSubtle) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, stringResource(R.string.open_settings), tint = TrackerDesign.colors.textSecondary)
                }
            }
        }
    }
}
