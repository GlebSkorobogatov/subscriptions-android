package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R

/** Visual tab identity only: routes and back stack remain owned by TrackerApp. */
enum class TrackerTab(@param:StringRes @get:StringRes val label: Int, val tag: String) {
    Subscriptions(R.string.subscriptions, "tab_subscriptions"),
    Finance(R.string.finance, "tab_finance"),
    Archive(R.string.archive, "tab_archive"),
}

/** Keep in Scaffold.bottomBar: its measured height reserves all insets and breathing room. */
@Composable
fun TrackerBottomNavigation(selected: TrackerTab, onSelect: (TrackerTab) -> Unit,
    modifier: Modifier = Modifier, windowInsets: WindowInsets = WindowInsets.navigationBars) {
    Box(modifier.fillMaxWidth()
        .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(start = TrackerDimensions.compactScreenPadding, end = TrackerDimensions.compactScreenPadding,
            top = TrackerDimensions.navigationGap, bottom = TrackerDimensions.navigationGap),
        contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = TrackerDimensions.navigationMaxWidth).fillMaxWidth()
            .heightIn(min = TrackerDimensions.navigationHeight).testTag("bottom_navigation"),
            shape = TrackerShapes.capsule, color = TrackerDesign.colors.surfaceRaised,
            tonalElevation = 0.dp, shadowElevation = 2.dp) {
            // Measure the actual wrapped labels, not an intrinsic estimate: scaled text can
            // need another line after the weighted widths are rounded to physical pixels.
            Row(Modifier.padding(4.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackerTab.entries.forEach { tab ->
                    val active = tab == selected
                    // Compose's animation clock uses Android's animator duration scale, including zero.
                    val fill by animateColorAsState(if (active) TrackerDesign.colors.selectedTabBg else Color.Transparent,
                        tween(TrackerDimensions.navigationAnimationMillis), label = "selected tab")
                    val foreground = if (active) TrackerDesign.colors.textPrimary else TrackerDesign.colors.textSecondary
                    val outline = if (active) Modifier.border(1.dp, TrackerDesign.colors.textSecondary, TrackerShapes.capsule) else Modifier
                    Column(Modifier.weight(1f).heightIn(min = 60.dp)
                        .clip(TrackerShapes.capsule).background(fill).then(outline)
                        .selectable(active, role = Role.Tab, onClick = { onSelect(tab) })
                        .testTag(tab.tag).padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        val icon = when (tab) {
                            TrackerTab.Subscriptions -> Icons.Default.Home
                            TrackerTab.Finance -> ImageVector.vectorResource(R.drawable.ic_finance)
                            TrackerTab.Archive -> ImageVector.vectorResource(R.drawable.ic_archive)
                        }
                        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = foreground)
                        Text(stringResource(tab.label), modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                // Keep the full line box when scaled font metrics contain fractional pixels.
                                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)),
                            color = foreground, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
