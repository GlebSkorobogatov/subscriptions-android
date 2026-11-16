package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

/** Content-sized header reserves safe drawing insets and lets scaled titles wrap naturally. */
@Composable
fun TrackerPageHeader(title: String,onBack: (() -> Unit)? = null,onSettings: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(horizontal = TrackerDimensions.screenPadding,vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { IconButton(it) { Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.navigate_back)) } }
        Text(title,Modifier.weight(1f).semantics { heading() },
            style = (if(onBack == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge).copy(
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None)))
        onSettings?.let {
            Surface(shape = CircleShape,color = TrackerDesign.colors.surfaceSubtle) {
                IconButton(it) { Icon(Icons.Default.Settings,stringResource(R.string.open_settings),tint = TrackerDesign.colors.textSecondary) }
            }
        }
    }
}
