package com.glebskorobogatov.subscriptiontracker.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Explicit saver for the whole state: the old nullable Boolean MutableState must never be
 * restored by the new non-null MutableState saver (which could retain a null value). */
internal val renewalOffsetsDialogSaver = Saver<MutableState<Boolean>,Any>(
    save = { if(it.value) "renewal-offsets" else "closed" },
    restore = { mutableStateOf(it == "renewal-offsets") },
)

@Composable
fun SettingsScreen(vm: SettingsViewModel, notifications: com.glebskorobogatov.subscriptiontracker.notifications.NotificationAccess? = null,
    onNotificationSettings: () -> Unit = {},onArchive: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName,0).versionName.orEmpty()
    }
    SettingsPane(state,version,vm::setTheme,vm::setOffset,vm::retryRead,vm::retryWrite,notifications,onNotificationSettings,onArchive,vm::setAnimations)
}

/** UI-only dialog selection. Preference writes and retry identity still belong to SettingsViewModel. */
@Composable
internal fun SettingsPane(state: SettingsUiState,version: String,onTheme: (Theme) -> Unit,
    onOffset: (Int,Boolean) -> Unit,onRetryRead: () -> Unit,onRetryWrite: () -> Unit,
    notifications: com.glebskorobogatov.subscriptiontracker.notifications.NotificationAccess? = null,
    onNotificationSettings: () -> Unit = {},onArchive: () -> Unit = {},onAnimations: (Boolean) -> Unit = {}) {
    val scroll = rememberScrollState()
    BindRootScroll(TrackerTab.Settings,scroll,canHide = state.phase == SettingsPhase.Content)
    val chrome = LocalRootContentPadding.current
    var offsetsOpen by rememberSaveable(saver = renewalOffsetsDialogSaver) { mutableStateOf(false) }
    val enabled = state.phase == SettingsPhase.Content && !state.pending
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(lineHeightStyle =
        LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None))) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding().verticalScroll(scroll)
            .padding(top = chrome.calculateTopPadding(),bottom = chrome.calculateBottomPadding())
            .padding(horizontal = trackerPageHorizontalPadding())
            .testTag("settings_screen")) {
            if(!offsetsOpen) SettingsFeedback(state,onRetryRead,onRetryWrite)
            state.data?.let { settings ->
                SettingsCard(R.string.settings_appearance) {
                    ThemeChoices(settings.theme,enabled,onTheme)
                    AnimationSwitch(settings.animationsEnabled,enabled,onAnimations)
                }
                SettingsCard(R.string.settings_section_notifications) {
                    ReminderTimeRow(settings.reminderLocalTime.format(DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT)))
                    val summary = offsetSummary(settings.renewalOffsets)
                    Row(Modifier.fillMaxWidth().heightIn(min = TrackerDimensions.minimumTouchTarget)
                        .clickable(enabled = enabled,role = Role.Button) { offsetsOpen = true }
                        .testTag("settings_payment_offsets"),
                        verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.settings_payment_offsets),style = MaterialTheme.typography.titleSmall)
                            Text(summary,color = TrackerDesign.colors.textSecondary,style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward,null,tint = TrackerDesign.colors.textSecondary)
                    }
                    notifications?.let { access ->
                        Text(stringResource(R.string.notifications_heading),style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(when {
                            !access.runtimeGranted -> R.string.notifications_permission_required
                            !access.appEnabled -> R.string.notifications_system_disabled
                            else -> R.string.notifications_allowed
                        }))
                        if (!access.renewalsEnabled) Text(stringResource(R.string.notifications_payment_disabled))
                        TextButton(onClick = onNotificationSettings,modifier = Modifier.heightIn(min = TrackerDimensions.minimumTouchTarget)) {
                            Text(stringResource(R.string.notifications_settings))
                        }
                    }
                    Text(stringResource(R.string.notifications_best_effort),color = TrackerDesign.colors.textSecondary,style = MaterialTheme.typography.bodySmall)
                }
                Surface(shape = TrackerShapes.card,color = TrackerDesign.colors.surfaceSubtle,tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    TrackerListRow(stringResource(R.string.archive),Modifier.testTag("settings_archive").semantics { heading() },onClick = onArchive)
                }
                SettingsCard(R.string.settings_about) {
                    Text(stringResource(if(version.isBlank()) R.string.settings_version_unknown else R.string.settings_version,version),Modifier.testTag("settings_version"),style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.settings_data),style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.settings_data_note),color = TrackerDesign.colors.textSecondary)
                    Text(stringResource(R.string.settings_local_note),color = TrackerDesign.colors.textSecondary)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
        if (offsetsOpen) {
            val selected = state.data?.renewalOffsets.orEmpty()
            val title = stringResource(R.string.settings_payment_offsets)
            val scrollTitle = scrollDialogHeading()
            AlertDialog(onDismissRequest = { offsetsOpen = false },modifier = Modifier.imePadding().testTag("settings_offsets_dialog").semantics { paneTitle = title },
                shape = TrackerShapes.card,containerColor = TrackerDesign.colors.surfaceRaised,tonalElevation = 0.dp,
                title = if(scrollTitle) null else ({ Text(title) }),text = {
                    Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if(scrollTitle) TrackerDialogHeading(title)
                        SettingsFeedback(state,onRetryRead,onRetryWrite)
                        Text(stringResource(R.string.settings_offsets_immediate),color = TrackerDesign.colors.textSecondary)
                        listOf(7,3,1,0).forEach { day ->
                            val text = offsetText(day)
                            val description = stringResource(R.string.settings_offset_description,stringResource(R.string.settings_payment_offsets),text)
                            Row(Modifier.fillMaxWidth().heightIn(min = TrackerDimensions.minimumTouchTarget)
                                .toggleable(day in selected,enabled = enabled,role = Role.Checkbox,onValueChange = { onOffset(day,it) })
                                .semantics { contentDescription = description }.testTag("settings_payment_$day"),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(day in selected,onCheckedChange = null,enabled = enabled)
                                Text(text,Modifier.weight(1f).padding(vertical = 12.dp))
                            }
                        }
                        if(selected.isEmpty() && state.data != null) Text(stringResource(R.string.settings_offsets_empty))
                    }
                },confirmButton = { TrackerSecondaryButton(stringResource(R.string.settings_done),{ offsetsOpen = false }) })
        }
    }
}

@Composable private fun SettingsCard(title: Int,content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title),Modifier.semantics { heading() },style = MaterialTheme.typography.titleSmall)
        Surface(shape = TrackerShapes.card,color = TrackerDesign.colors.surfaceSubtle,tonalElevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement = Arrangement.spacedBy(12.dp),content = content)
        }
    }
}

@Composable private fun ThemeChoices(selected: Theme,enabled: Boolean,onTheme: (Theme) -> Unit) {
    val options = listOf(Theme.LIGHT to R.string.settings_theme_light,Theme.DARK to R.string.settings_theme_dark,Theme.SYSTEM to R.string.settings_theme_system)
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        // 360 dp is the window breakpoint. Card padding makes the row itself narrower, so the
        // switch to a column uses the space three captions still need, or a large font.
        val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f
        if(stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (theme,label) -> ThemeChoice(theme,label,selected == theme,enabled,stacked = true,onTheme) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { (theme,label) -> ThemeChoice(theme,label,selected == theme,enabled,stacked = false,onTheme,Modifier.weight(1f)) }
        }
    }
}

@Composable private fun ThemeChoice(theme: Theme,label: Int,selected: Boolean,enabled: Boolean,stacked: Boolean,
    onTheme: (Theme) -> Unit,modifier: Modifier = Modifier) {
    val description = stringResource(if(selected) R.string.tracker_selected else R.string.tracker_not_selected)
    val choice = modifier.fillMaxWidth().heightIn(min = TrackerDimensions.minimumTouchTarget)
        .selectable(selected,enabled = enabled,role = Role.RadioButton,onClick = { onTheme(theme) })
        .semantics { stateDescription = description }.testTag("settings_theme_${theme.name}")
    if(stacked) Row(choice,horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.CenterVertically) {
        ThemeThumbnail(theme,selected,Modifier.size(width = 72.dp,height = 84.dp))
        Text(stringResource(label),Modifier.weight(1f),style = MaterialTheme.typography.bodyMedium)
    } else Column(choice,horizontalAlignment = Alignment.CenterHorizontally) {
        ThemeThumbnail(theme,selected,Modifier.fillMaxWidth().height(84.dp))
        Text(stringResource(label),Modifier.fillMaxWidth().padding(top = 8.dp),style = MaterialTheme.typography.bodyMedium,textAlign = TextAlign.Center)
    }
}

@Composable private fun ThemeThumbnail(theme: Theme,selected: Boolean,modifier: Modifier) {
    Box(modifier.then(if(selected) Modifier.border(2.dp,TrackerDesign.colors.textPrimary,TrackerShapes.field) else Modifier).clip(TrackerShapes.field)) {
        ThemePreview(theme,Modifier.fillMaxSize())
        if(selected) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).testTag("settings_theme_check")) {
            Box(Modifier.size(20.dp).background(TrackerDesign.colors.surface,CircleShape).clearAndSetSemantics {},
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check,null,Modifier.size(14.dp),tint = TrackerDesign.colors.textPrimary)
            }
        }
    }
}

@Composable private fun ThemePreview(theme: Theme,modifier: Modifier) {
    if(theme == Theme.SYSTEM) Row(modifier) {
        ThemePreviewFace(LightTrackerColors,Modifier.weight(1f).fillMaxHeight())
        ThemePreviewFace(DarkTrackerColors,Modifier.weight(1f).fillMaxHeight())
    } else ThemePreviewFace(if(theme == Theme.DARK) DarkTrackerColors else LightTrackerColors,modifier)
}

@Composable private fun ThemePreviewFace(colors: TrackerColors,modifier: Modifier) {
    Column(modifier.background(colors.background).padding(horizontal = 6.dp,vertical = 8.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth(0.7f).height(6.dp).background(colors.textPrimary,CircleShape))
        Box(Modifier.fillMaxWidth().height(5.dp).background(colors.surfaceSubtle,CircleShape))
        Box(Modifier.fillMaxWidth(0.62f).height(5.dp).background(colors.textSecondary.copy(alpha = 0.55f),CircleShape))
        Spacer(Modifier.weight(1f))
        Box(Modifier.align(Alignment.CenterHorizontally).width(26.dp).height(8.dp).background(colors.surfaceRaised,CircleShape))
    }
}

@Composable private fun AnimationSwitch(enabledValue: Boolean,enabled: Boolean,onChange: (Boolean) -> Unit) {
    val value = stringResource(if(enabledValue) R.string.settings_animations_on else R.string.settings_animations_off)
    Row(Modifier.fillMaxWidth().heightIn(min = TrackerDimensions.minimumTouchTarget)
        .toggleable(enabledValue,enabled = enabled,role = Role.Switch,onValueChange = onChange)
        .testTag("settings_animations"),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.settings_animations),style = MaterialTheme.typography.titleSmall)
            Text(value,color = TrackerDesign.colors.textSecondary,style = MaterialTheme.typography.bodySmall)
        }
        Switch(enabledValue,onCheckedChange = null,enabled = enabled,modifier = Modifier.clearAndSetSemantics {},
            colors = SwitchDefaults.colors(checkedThumbColor = TrackerDesign.colors.primaryButtonText,checkedTrackColor = TrackerDesign.colors.primaryButtonBg,
                uncheckedThumbColor = TrackerDesign.colors.surface,uncheckedTrackColor = TrackerDesign.colors.divider,uncheckedBorderColor = TrackerDesign.colors.textSecondary))
    }
}

@Composable private fun offsetText(day: Int) = stringResource(
    if(day == 0) R.string.settings_payment_day else R.string.settings_days_before,day)

@Composable private fun offsetSummary(offsets: Set<Int>): String =
    if(offsets.isEmpty()) stringResource(R.string.settings_offsets_empty)
    else offsets.sortedDescending().map { offsetText(it) }.joinToString(", ")

/** Preserve the label's width: the large-font layout gives the value its own horizontal line. */
@Composable internal fun ReminderTimeRow(value: String) {
    val description = stringResource(R.string.settings_time,value)
    val titleStyle = MaterialTheme.typography.titleSmall.copy(lineHeightStyle =
        LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None))
    val clockWidth = with(LocalDensity.current) { titleStyle.fontSize.toDp() } * 4
    val captionStyle = MaterialTheme.typography.bodySmall.copy(lineHeightStyle =
        LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None))
    Row(Modifier.fillMaxWidth().heightIn(min = TrackerDimensions.minimumTouchTarget)
        .semantics { stateDescription = description }
        .testTag("settings_time").padding(vertical = 16.dp),horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.CenterVertically) {
        Icon(ImageVector.vectorResource(R.drawable.ic_clock),null,Modifier.size(24.dp),tint = TrackerDesign.colors.textSecondary)
        BoxWithConstraints(Modifier.weight(1f)) {
            @Composable fun label(modifier: Modifier = Modifier) { Column(modifier,verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_notification_time),Modifier.fillMaxWidth().testTag("settings_time_label"),style = titleStyle)
                Text(stringResource(R.string.settings_time_subtitle),Modifier.fillMaxWidth().testTag("settings_time_subtitle"),color = TrackerDesign.colors.textSecondary,style = captionStyle)
            } }
            @Composable fun time() {
                Text(value,Modifier.width(clockWidth).testTag("settings_time_value"),style = titleStyle,maxLines = 1,textAlign = TextAlign.End)
            }
            val title = stringResource(R.string.settings_notification_time)
            val subtitle = stringResource(R.string.settings_time_subtitle)
            val measurer = rememberTextMeasurer()
            val labelMax = (maxWidth - clockWidth - 12.dp).coerceAtLeast(0.dp)
            val labelPx = with(LocalDensity.current) { labelMax.roundToPx() }
            val titleFit = measurer.measure(title,titleStyle,constraints = Constraints(maxWidth = labelPx))
            val subtitleFit = measurer.measure(subtitle,captionStyle,constraints = Constraints(maxWidth = labelPx))
            val horizontalFits = labelPx > 0 && titleFit.lineCount <= 2 && !titleFit.didOverflowWidth &&
                !titleFit.didOverflowHeight && !subtitleFit.didOverflowWidth && !subtitleFit.didOverflowHeight
            // Ordinary font keeps 09:00 beside the title, including a 320 dp window. Large font may stack.
            if(LocalDensity.current.fontScale >= 1.5f || !horizontalFits) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                label()
                Row(Modifier.align(Alignment.End),horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.CenterVertically) { time() }
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.CenterVertically) {
                label(Modifier.weight(1f)); time()
            }
        }
    }
}

@Composable private fun SettingsFeedback(state: SettingsUiState,onRetryRead: () -> Unit,onRetryWrite: () -> Unit) {
    if(state.phase == SettingsPhase.Loading || state.phase == SettingsPhase.Saving) {
        CircularProgressIndicator(Modifier.padding(top = 12.dp)); Text(stringResource(if(state.phase == SettingsPhase.Loading) R.string.settings_loading else R.string.settings_saving))
    }
    state.error?.let { Text(stringResource(it),color = TrackerDesign.colors.error,modifier = Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
    if(state.phase == SettingsPhase.Error || state.pending && state.phase != SettingsPhase.Saving) {
        TrackerSecondaryButton(stringResource(R.string.retry_loading),onRetryRead)
        if(state.pending && state.phase == SettingsPhase.Content) TrackerSecondaryButton(stringResource(R.string.settings_retry_write),onRetryWrite)
    }
}
