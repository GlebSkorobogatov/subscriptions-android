package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R

enum class TrackerTab(@param:StringRes @get:StringRes val label: Int,val tag: String) {
    Subscriptions(R.string.subscriptions,"tab_subscriptions"),
    Finance(R.string.finance,"tab_finance"),
    Settings(R.string.settings,"tab_settings"),
}
object TrackerChrome {
    const val islandAlpha = 0.85f
    const val headerAlpha = 0.94f
    const val scrollThresholdDp = 24f
}

/** Only the background is translucent. Content and the selected capsule stay opaque. */
@Composable
fun TrackerBottomNavigation(selected: TrackerTab,onSelect: (TrackerTab) -> Unit,
    modifier: Modifier = Modifier,windowInsets: WindowInsets = WindowInsets.navigationBars,enabled: Boolean = true) {
    Box(modifier.then(if(!enabled) Modifier.clearAndSetSemantics {} else Modifier).fillMaxWidth().windowInsetsPadding(windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(horizontal = TrackerDimensions.compactScreenPadding,vertical = TrackerDimensions.navigationGap),
        contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = TrackerDimensions.navigationMaxWidth).fillMaxWidth()
            .height(TrackerDimensions.navigationHeight).testTag("bottom_navigation"),shape = TrackerShapes.capsule,
            color = TrackerDesign.colors.surfaceRaised.copy(alpha = TrackerChrome.islandAlpha),tonalElevation = 0.dp,shadowElevation = 2.dp) {
            Row(Modifier.padding(6.dp).selectableGroup(),verticalAlignment = Alignment.CenterVertically) {
                TrackerTab.entries.forEach { tab ->
                    val active = tab == selected
                    val icon = when(tab) {
                        TrackerTab.Subscriptions -> Icons.AutoMirrored.Outlined.List
                        TrackerTab.Finance -> ImageVector.vectorResource(R.drawable.ic_finance)
                        TrackerTab.Settings -> Icons.Outlined.Settings
                    }
                    Box(Modifier.weight(1f).heightIn(min = TrackerDimensions.minimumTouchTarget).clip(TrackerShapes.capsule)
                        .background(if(active) TrackerDesign.colors.selectedTabBg else Color.Transparent)
                        .selectable(active,enabled = enabled,role = Role.Tab,onClick = { if(!active) onSelect(tab) })
                        .testTag(tab.tag),contentAlignment = Alignment.Center) {
                        Icon(icon,stringResource(tab.label),Modifier.size(if(active) 26.dp else 24.dp),
                            tint = if(active) TrackerDesign.colors.textPrimary else TrackerDesign.colors.textSecondary)
                    }
                }
            }
        }
    }
}
