package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.glebskorobogatov.subscriptiontracker.R

@Composable
internal fun trackerButtonColors(destructive: Boolean = false): ButtonColors {
    val c = TrackerDesign.colors
    return ButtonDefaults.buttonColors(containerColor = if(destructive) c.error else c.primaryButtonBg,
        contentColor = if(destructive) c.onError else c.primaryButtonText,
        disabledContainerColor = c.surfaceSubtle,disabledContentColor = c.textSecondary)
}

@Composable
fun TrackerTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    destructive: Boolean = false) {
    TextButton(onClick,modifier.heightIn(min = TrackerDimensions.minimumTouchTarget),enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = if(destructive) TrackerDesign.colors.error else TrackerDesign.colors.textPrimary,
            disabledContentColor = TrackerDesign.colors.textSecondary)) { Text(text) }
}

@Composable
fun TrackerDestructiveButton(text: String,onClick: () -> Unit,modifier: Modifier = Modifier,enabled: Boolean = true) {
    Button(onClick,modifier.heightIn(min = TrackerDimensions.buttonHeight),enabled = enabled,
        colors = trackerButtonColors(destructive = true),shape = TrackerShapes.capsule) { Text(text) }
}

@Composable
fun TrackerIconButton(icon: ImageVector, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    require(description.isNotBlank())
    IconButton(onClick,modifier.sizeIn(minWidth = TrackerDimensions.minimumTouchTarget,minHeight = TrackerDimensions.minimumTouchTarget),
        enabled = enabled,colors = IconButtonDefaults.iconButtonColors(contentColor = TrackerDesign.colors.textPrimary,
            disabledContentColor = TrackerDesign.colors.textSecondary)) { Icon(icon,description) }
}

/** Single/multiple selection share the same neutral visual and an explicit non-color check. */
@Composable
fun TrackerSelectedCapsule(text: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, multiple: Boolean = false) {
    val label = stringResource(if(selected) R.string.tracker_selected else R.string.tracker_not_selected)
    val interaction = if(multiple) Modifier.toggleable(selected,enabled = enabled,role = Role.Checkbox,onValueChange = { onClick() })
        else Modifier.selectable(selected,enabled = enabled,role = Role.RadioButton,onClick = onClick)
    Surface(modifier.then(interaction).semantics { this.selected = selected; stateDescription = label },
        shape = TrackerShapes.capsule,color = if(selected) TrackerDesign.colors.selectedTabBg else TrackerDesign.colors.surfaceSubtle,
        contentColor = if(enabled && selected) TrackerDesign.colors.textPrimary else TrackerDesign.colors.textSecondary) {
        Row(Modifier.sizeIn(minWidth = TrackerDimensions.minimumTouchTarget,minHeight = TrackerDimensions.minimumTouchTarget).padding(horizontal = 16.dp,vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),verticalAlignment = Alignment.CenterVertically) {
            if(selected) Icon(Icons.Outlined.Check,null,Modifier.size(20.dp))
            Text(text,fontWeight = if(selected) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal)
        }
    }
}

enum class TrackerSurfaceRole { PLAIN, SUBTLE, RAISED, MODAL }
@Composable
fun TrackerSurface(modifier: Modifier = Modifier, role: TrackerSurfaceRole = TrackerSurfaceRole.PLAIN,
    content: @Composable () -> Unit) {
    val c = TrackerDesign.colors
    Surface(modifier,color = when(role) {
        TrackerSurfaceRole.PLAIN -> c.surface
        TrackerSurfaceRole.SUBTLE -> c.surfaceSubtle
        TrackerSurfaceRole.RAISED, TrackerSurfaceRole.MODAL -> c.surfaceRaised
    },shape = if(role == TrackerSurfaceRole.PLAIN) androidx.compose.ui.graphics.RectangleShape else TrackerShapes.card,
        tonalElevation = 0.dp,content = content)
}

/**
 * Material's sheet motion scheme is internal and ignores the app animation setting.
 * A zero duration therefore uses a focusable popup with no window animation.
 * The system photo picker is a separate activity and is not wrapped here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerBottomSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, skipPartiallyExpanded: Boolean = false,
    footer: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    if (trackerMotionDuration(LocalTrackerAnimations.current) == 0) {
        TrackerImmediateSheet(onDismiss, modifier, footer, content)
    } else {
        TrackerModalSheet(onDismiss, modifier, skipPartiallyExpanded, footer, content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerImmediateSheet(onDismiss: () -> Unit, modifier: Modifier,
    footer: (@Composable ColumnScope.() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Popup(onDismissRequest = onDismiss, properties = PopupProperties(
        focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false, clippingEnabled = false)) {
        Box(Modifier.fillMaxSize().semantics { dialog() }) {
            Box(Modifier.matchParentSize().background(BottomSheetDefaults.ScrimColor)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .clearAndSetSemantics {})
            Surface(modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(BottomSheetDefaults.windowInsets)
                .pointerInput(Unit) { detectTapGestures { } },
                color = TrackerDesign.colors.surfaceRaised, contentColor = TrackerDesign.colors.textPrimary,
                shape = TrackerShapes.card, tonalElevation = 0.dp) {
                TrackerSheetBody(footer, content)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerModalSheet(onDismiss: () -> Unit, modifier: Modifier, skipPartiallyExpanded: Boolean,
    footer: (@Composable ColumnScope.() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier, sheetState = sheetState,
        containerColor = TrackerDesign.colors.surfaceRaised, contentColor = TrackerDesign.colors.textPrimary,
        shape = TrackerShapes.card, tonalElevation = 0.dp) {
        TrackerSheetBody(footer, content)
    }
}

@Composable
private fun TrackerSheetBody(footer: (@Composable ColumnScope.() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    if (footer == null) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(TrackerDimensions.modalPadding), content = content)
    } else {
        Column(Modifier.fillMaxWidth().fillMaxHeight().imePadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(TrackerDimensions.modalPadding), content = content)
            Column(Modifier.fillMaxWidth().background(TrackerDesign.colors.background)
                .padding(horizontal = TrackerDimensions.modalPadding)
                .padding(top = TrackerDimensions.itemSpacing, bottom = TrackerDimensions.modalPadding),
                verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing), content = footer)
        }
    }
}
