package com.glebskorobogatov.subscriptiontracker.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerDimensions

/** Content-sized secondary header: the long editor title can wrap with the system font scale. */
@Composable
fun EditorHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(horizontal = TrackerDimensions.screenPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.navigate_back)) }
        Text(title,Modifier.weight(1f).semantics { heading() },style = MaterialTheme.typography.titleLarge.copy(
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None)))
    }
}
