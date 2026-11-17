package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp

/** In a short window a fixed multi-line title can consume the whole form viewport. */
@Composable internal fun scrollDialogHeading(): Boolean {
    val pixels = LocalWindowInfo.current.containerSize.height
    val height = with(LocalDensity.current) { pixels.toDp() }
    return pixels > 0 && height < 480.dp
}

/** Keep the same scaled typography; scrolling, not reducing fontScale, makes room for fields. */
@Composable internal fun TrackerDialogHeading(title: String) {
    Text(title,Modifier.semantics { heading() },style = MaterialTheme.typography.titleLarge.copy(
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None)))
}
