package com.glebskorobogatov.subscriptiontracker.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
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
    onNotificationSettings: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName,0).versionName.orEmpty()
    }
    SettingsPane(state,version,vm::setTheme,vm::setOffset,vm::retryRead,vm::retryWrite,notifications,onNotificationSettings)
}

/** UI-only dialog selection. Preference writes and retry identity still belong to SettingsViewModel. */
@Composable
internal fun SettingsPane(state: SettingsUiState,version: String,onTheme: (Theme) -> Unit,
    onOffset: (Int,Boolean) -> Unit,onRetryRead: () -> Unit,onRetryWrite: () -> Unit,
    notifications: com.glebskorobogatov.subscriptiontracker.notifications.NotificationAccess? = null,
    onNotificationSettings: () -> Unit = {}) {
    var offsetsOpen by rememberSaveable(saver = renewalOffsetsDialogSaver) { mutableStateOf(false) }
    val enabled = state.phase == SettingsPhase.Content && !state.pending
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(lineHeightStyle =
        LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None))) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = TrackerDimensions.screenPadding)
            .testTag("settings_screen")) {
            if(!offsetsOpen) SettingsFeedback(state,onRetryRead,onRetryWrite)
            state.data?.let { settings ->
                SettingsHeading(R.string.settings_appearance)
                Column(Modifier.selectableGroup()) {
                    listOf(Theme.SYSTEM to R.string.settings_theme_system,Theme.LIGHT to R.string.settings_theme_light,Theme.DARK to R.string.settings_theme_dark).forEach { (theme,label) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = TrackerDimensions.minimumTouchTarget)
                            .selectable(settings.theme == theme,enabled = enabled,role = Role.RadioButton,onClick = { onTheme(theme) })
                            .testTag("settings_theme_${theme.name}"),horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(settings.theme == theme,onClick = null,enabled = enabled)
                            Text(stringResource(label),Modifier.weight(1f).padding(vertical = 12.dp))
                        }
                    }
                }
                SettingsHeading(R.string.settings_reminders)
                ReminderTimeRow(settings.reminderLocalTime.format(DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT)))
                HorizontalDivider(color = TrackerDesign.colors.divider)
                listOf(R.string.settings_payment_offsets).forEach { label ->
                    val summary = offsetSummary(settings.renewalOffsets)
                    Row(Modifier.fillMaxWidth().heightIn(min = TrackerDimensions.minimumTouchTarget)
                        .clickable(enabled = enabled,role = Role.Button) { offsetsOpen = true }
                        .testTag("settings_payment_offsets").padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(label),style = MaterialTheme.typography.titleSmall)
                            Text(summary,color = TrackerDesign.colors.textSecondary,style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward,null,tint = TrackerDesign.colors.textSecondary)
                    }
                    HorizontalDivider(color = TrackerDesign.colors.divider)
                }
                notifications?.let { access ->
                    SettingsHeading(R.string.notifications_heading)
                    Text(stringResource(when {
                        !access.runtimeGranted -> R.string.notifications_permission_required
                        !access.appEnabled -> R.string.notifications_system_disabled
                        else -> R.string.notifications_allowed
                    }))
                    if (!access.renewalsEnabled) Text(stringResource(R.string.notifications_payment_disabled))
                    TextButton(onClick = onNotificationSettings) { Text(stringResource(R.string.notifications_settings)) }
                }
                Text(stringResource(R.string.notifications_best_effort),Modifier.padding(top = 16.dp),color = TrackerDesign.colors.textSecondary,style = MaterialTheme.typography.bodySmall)
                SettingsHeading(R.string.settings_data)
                Text(stringResource(R.string.settings_data_note),color = TrackerDesign.colors.textSecondary)
                SettingsHeading(R.string.settings_about)
                Text(stringResource(if(version.isBlank()) R.string.settings_version_unknown else R.string.settings_version,version),Modifier.testTag("settings_version"),style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.settings_local_note),Modifier.padding(top = 8.dp),color = TrackerDesign.colors.textSecondary)
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

@Composable private fun SettingsHeading(label: Int) {
    Text(stringResource(label),Modifier.padding(top = 24.dp,bottom = 8.dp).semantics { heading() },style = MaterialTheme.typography.titleSmall)
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
            if(maxWidth < 240.dp || LocalDensity.current.fontScale >= 1.5f) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
