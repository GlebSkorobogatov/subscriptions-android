package com.glebskorobogatov.subscriptiontracker.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import com.glebskorobogatov.subscriptiontracker.ui.editor.EditorDateSheet
import com.glebskorobogatov.subscriptiontracker.ui.editor.EditorPeriodPreset
import com.glebskorobogatov.subscriptiontracker.ui.editor.editorFrequencySelection
import com.glebskorobogatov.subscriptiontracker.ui.editor.epochDayDate
import com.glebskorobogatov.subscriptiontracker.ui.editor.initialDateMillis
import java.util.UUID

@Composable
fun DetailsScreen(vm: DetailsViewModel, id: UUID, exitRequest: Int, onBack: () -> Unit, onEdit: () -> Unit,
    onDeleted: () -> Unit, onArchived: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    var menu by rememberSaveable { mutableStateOf(false) }
    fun leave() { if (state.phase != DetailsPhase.OperationInProgress && !state.frozen) onBack() }
    BackHandler {
        when {
            menu -> menu = false
            state.action != null -> vm.dismiss()
            else -> leave()
        }
    }
    LaunchedEffect(state.action, state.phase) {
        if (state.action != null || state.phase == DetailsPhase.OperationInProgress) menu = false
    }
    var lastExit by remember { mutableIntStateOf(exitRequest) }
    LaunchedEffect(exitRequest) { if (lastExit != exitRequest) { lastExit = exitRequest; leave() } }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    LaunchedEffect(state.archived) { if (state.archived) onArchived() }
    Box(Modifier.fillMaxSize()) {
        DetailsPane(state, id, menu, { menu = it }, { leave() }, onEdit, { leave() }, vm::open, vm::refresh)
        if (state.action != null) DetailsActionDialog(state, vm::change, vm::submit, vm::dismiss, vm::refresh,
            vm::changeFrequency, vm::setFrequencyMode)
    }
}

/** Read-only presentation adapter; callbacks still enter the existing ViewModel commands. */
@Composable
internal fun DetailsPane(state: DetailsUiState, id: UUID, menu: Boolean, onMenu: (Boolean) -> Unit,
    onBack: () -> Unit, onEdit: () -> Unit, onMissingBack: () -> Unit, onAction: (DetailsAction) -> Unit, onRefresh: () -> Unit) {
    val subscription = state.data?.subscription
    val operable = state.phase == DetailsPhase.Content && state.action == null && !state.frozen
    val backEnabled = state.action == null && state.phase != DetailsPhase.OperationInProgress && !state.frozen
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))) {
        Column(Modifier.fillMaxSize().testTag("details_$id")) {
            DetailsHeader(onBack, backEnabled, menu, onMenu, operable, subscription?.state, onEdit, onAction)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = trackerPageHorizontalPadding())
                .padding(top = TrackerDimensions.itemSpacing, bottom = TrackerDimensions.sectionSpacing)
                .testTag("details_content"), verticalArrangement = Arrangement.spacedBy(TrackerDimensions.contentSpacing)) {
                state.error?.takeIf { state.action == null }?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                when (state.phase) {
                    DetailsPhase.Loading -> TrackerLoading(stringResource(R.string.details_loading))
                    DetailsPhase.Error -> TrackerPrimaryButton(stringResource(R.string.retry_loading), onRefresh, Modifier.fillMaxWidth())
                    DetailsPhase.NotFound -> {
                        Text(stringResource(R.string.subscription_not_found))
                        TrackerSecondaryButton(stringResource(R.string.return_to_subscriptions), onMissingBack, Modifier.fillMaxWidth(),
                            enabled = backEnabled)
                    }
                    else -> state.data?.let { detail -> DetailsBody(detail, state.phase == DetailsPhase.OperationInProgress,
                        showReload = state.action == null && (state.frozen || state.error != null), onRefresh = onRefresh) }
                }
            }
            if (subscription != null && state.phase != DetailsPhase.NotFound && state.phase != DetailsPhase.Loading && state.phase != DetailsPhase.Error) {
                val (label, run) = primaryAction(subscription.state, onEdit, onAction)
                TrackerPrimaryButton(stringResource(label), run,
                    Modifier.fillMaxWidth().padding(horizontal = trackerPageHorizontalPadding())
                        .padding(bottom = TrackerDimensions.itemSpacing).testTag("details_primary"),
                    enabled = operable)
            }
        }
    }
}

private fun primaryAction(status: SubscriptionState, onEdit: () -> Unit, onAction: (DetailsAction) -> Unit): Pair<Int, () -> Unit> =
    when (status) {
        SubscriptionState.ACTIVE -> R.string.details_primary_edit to onEdit
        SubscriptionState.CANCELLED -> R.string.details_primary_resume to { onAction(DetailsAction.Reactivate) }
        SubscriptionState.ARCHIVED -> R.string.details_primary_restore to { onAction(DetailsAction.Reactivate) }
    }

@Composable
private fun DetailsHeader(onBack: () -> Unit, backEnabled: Boolean, menu: Boolean, onMenu: (Boolean) -> Unit,
    menuEnabled: Boolean, status: SubscriptionState?, onEdit: () -> Unit, onAction: (DetailsAction) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = trackerPageHorizontalPadding(), vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrackerIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.navigate_back), onBack, enabled = backEnabled)
        Text(stringResource(R.string.details_title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (status != null) Box {
            TrackerIconButton(ImageVector.vectorResource(R.drawable.ic_more_vert), stringResource(R.string.details_menu),
                { onMenu(!menu) }, Modifier.testTag("details_menu"), enabled = menuEnabled)
            DropdownMenu(menu, { onMenu(false) }, Modifier.testTag("details_menu_sheet"),
                containerColor = TrackerDesign.colors.surfaceRaised, tonalElevation = 0.dp) {
                detailsMenu(status, onEdit, onAction).forEach { item ->
                    DropdownMenuItem(
                        text = { Text(stringResource(item.label)) },
                        onClick = { onMenu(false); item.run() },
                        leadingIcon = { Icon(ImageVector.vectorResource(item.icon), null) },
                        modifier = Modifier.testTag(item.tag).heightIn(min = TrackerDimensions.minimumTouchTarget),
                        colors = MenuDefaults.itemColors(
                            textColor = if (item.destructive) TrackerDesign.colors.error else TrackerDesign.colors.textPrimary,
                            leadingIconColor = if (item.destructive) TrackerDesign.colors.error else TrackerDesign.colors.textPrimary))
                }
            }
        }
    }
}

private data class DetailsMenuItem(val label: Int, val icon: Int, val tag: String, val destructive: Boolean, val run: () -> Unit)

private fun detailsMenu(status: SubscriptionState, onEdit: () -> Unit, onAction: (DetailsAction) -> Unit): List<DetailsMenuItem> {
    fun item(label: Int, icon: Int, tag: String, action: DetailsAction, destructive: Boolean = false) =
        DetailsMenuItem(label, icon, tag, destructive) { onAction(action) }
    val edit = DetailsMenuItem(R.string.details_primary_edit, R.drawable.ic_edit, "details_action_edit", false, onEdit)
    val cancel = item(R.string.details_mark_cancelled, R.drawable.ic_event, "details_action_disable", DetailsAction.Disable)
    val end = item(R.string.details_edit_end, R.drawable.ic_event, "details_action_edit_end", DetailsAction.EditEnd)
    val archive = item(R.string.details_archive, R.drawable.ic_archive, "details_action_archive", DetailsAction.Archive)
    val delete = item(R.string.details_delete, R.drawable.ic_delete, "details_action_delete", DetailsAction.Delete, destructive = true)
    return when (status) {
        SubscriptionState.ACTIVE -> listOf(cancel, archive, delete)
        SubscriptionState.CANCELLED -> listOf(edit, end, archive, delete)
        SubscriptionState.ARCHIVED -> listOf(edit, delete)
    }
}

@Composable
private fun DetailsBody(detail: com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionDetails,
    busy: Boolean, showReload: Boolean, onRefresh: () -> Unit) {
    val subscription = detail.subscription
    val narrow = LocalDensity.current.fontScale >= 1.5f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stack = narrow || maxWidth < 360.dp
        if (stack) Column(verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
            DetailsIdentity(subscription)
            DetailsPrice(subscription)
        } else Row(horizontalArrangement = Arrangement.spacedBy(TrackerDimensions.contentSpacing), verticalAlignment = Alignment.Top) {
            DetailsMark(subscription)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
                Text(subscription.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().semantics { heading() })
                subscription.accountLabel?.takeIf(String::isNotBlank)?.let { Text(it, color = TrackerDesign.colors.textSecondary) }
                DetailsPrice(subscription)
            }
        }
    }
    val status = when (subscription.state) {
        SubscriptionState.ACTIVE -> R.string.details_active
        SubscriptionState.CANCELLED -> R.string.status_cancelled
        SubscriptionState.ARCHIVED -> R.string.status_archived
    }
    TrackerStatusLabel(stringResource(status), secondary = subscription.state != SubscriptionState.ACTIVE, style = MaterialTheme.typography.titleMedium)
    val event = detail.period.due
    val storedEnd = subscription.cancelledAccessEndExclusive
    if (detail.calendarError) Text(stringResource(R.string.projection_calendar_error),
        modifier = Modifier.testTag("details_calendar_error"), color = TrackerDesign.colors.error)
    if (event != null) {
        Text(stringResource(if (subscription.state == SubscriptionState.CANCELLED) R.string.access_until else R.string.expected_on, event.toString()))
        val days = detail.period.remainingDays
        if (days == 0L) Text(stringResource(R.string.event_today))
        else if (days != null && days > 0) Text(stringResource(R.string.days_remaining, days))
    } else if (storedEnd != null) {
        Text(stringResource(R.string.access_until, storedEnd.toString()))
    } else if (subscription.state == SubscriptionState.ARCHIVED && subscription.archivedAt != null) {
        Text(stringResource(R.string.archived_on, subscription.archivedAt.toString()), color = TrackerDesign.colors.textSecondary)
    } else Text(stringResource(if (subscription.state == SubscriptionState.CANCELLED) R.string.access_unknown else R.string.event_unknown),
        color = TrackerDesign.colors.textSecondary)
    TrackerPeriodIndicator(detail.period.remainingFraction, detail.period.remainingDays, subscription.state,
        stringResource(R.string.progress_status, stringResource(status)),
        modifier = Modifier.testTag("details_progress_track"),
        indicatorModifier = Modifier.testTag("details_progress"))
    if (busy) CircularProgressIndicator()
    if (showReload && !busy) TrackerSecondaryButton(stringResource(R.string.editor_reload), onRefresh, Modifier.fillMaxWidth())
}

@Composable
private fun DetailsIdentity(subscription: com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity) {
    Row(horizontalArrangement = Arrangement.spacedBy(TrackerDimensions.contentSpacing), verticalAlignment = Alignment.CenterVertically) {
        DetailsMark(subscription)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(subscription.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().semantics { heading() })
            subscription.accountLabel?.takeIf(String::isNotBlank)?.let { Text(it, color = TrackerDesign.colors.textSecondary) }
        }
    }
}

@Composable
private fun DetailsMark(subscription: com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity) {
    Box(Modifier.size(TrackerDimensions.detailsLogoSize).testTag("details_icon"), contentAlignment = Alignment.Center) {
        SubscriptionImage(subscription.name, subscription.logoAssetId, size = TrackerDimensions.detailsLogoSize, catalogId = subscription.catalogLogoId)
    }
}

@Composable
private fun DetailsPrice(subscription: com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity) {
    Column(Modifier.fillMaxWidth().testTag("details_price"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TrackerMoney(subscription.priceMinor, Modifier.fillMaxWidth(),
            style = (if (LocalDensity.current.fontScale >= 1.5f) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall)
                .copy(textAlign = TextAlign.Start))
        Text(periodText(subscription.intervalUnit, subscription.intervalCount), color = TrackerDesign.colors.textSecondary)
    }
}

@Composable
internal fun periodText(unit: FrequencyUnit, count: Int): String {
    val named = when {
        count == 1 && unit == FrequencyUnit.DAY -> R.string.home_period_day
        count == 1 && unit == FrequencyUnit.WEEK -> R.string.home_period_week
        count == 1 && unit == FrequencyUnit.MONTH -> R.string.home_period_month
        count == 1 && unit == FrequencyUnit.YEAR -> R.string.home_period_year
        unit == FrequencyUnit.MONTH && count == 3 -> R.string.home_period_quarter
        unit == FrequencyUnit.MONTH && count == 6 -> R.string.home_period_half_year
        else -> null
    }
    if (named != null) return stringResource(named)
    val pattern = when (unit) {
        FrequencyUnit.DAY -> R.string.frequency_days
        FrequencyUnit.WEEK -> R.string.frequency_weeks
        FrequencyUnit.MONTH -> R.string.frequency_months
        FrequencyUnit.YEAR -> R.string.frequency_years
    }
    return stringResource(pattern, count)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailsActionDialog(state: DetailsUiState, onChange: (String, String) -> Unit,
    onSubmit: () -> Unit, onDismiss: () -> Unit, onRefresh: () -> Unit,
    onFrequency: (String, String) -> Unit = { unit, count -> onChange("unit", unit); onChange("count", count) },
    onFrequencyMode: (Boolean) -> Unit = {}) {
    val action = requireNotNull(state.action)
    val busy = state.phase == DetailsPhase.OperationInProgress
    val title = stringResource(when (action) {
        DetailsAction.EditEnd -> R.string.details_edit_end
        DetailsAction.Disable -> R.string.details_mark_cancelled
        DetailsAction.Archive -> R.string.details_archive
        DetailsAction.Reactivate -> if (state.data?.subscription?.state == SubscriptionState.ARCHIVED) R.string.details_primary_restore else R.string.details_primary_resume
        DetailsAction.Delete -> R.string.details_delete
    })
    when (action) {
        DetailsAction.Delete -> AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("details_dialog").semantics { paneTitle = title },
            shape = TrackerShapes.card, containerColor = TrackerDesign.colors.surfaceRaised, tonalElevation = 0.dp,
            title = { Text(title, modifier = Modifier.semantics { heading() }) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                Text(stringResource(R.string.details_delete_warning))
                if (state.frozen) Text(stringResource(R.string.details_retry_same))
                DetailsReload(state, onRefresh)
                if (busy) CircularProgressIndicator()
            } },
            confirmButton = { TrackerDestructiveButton(stringResource(if (state.frozen) R.string.details_retry else R.string.details_delete_confirm),
                onSubmit, Modifier.testTag("details_submit"), enabled = !busy && !state.conflicted) },
            dismissButton = { TextButton(onClick = onDismiss, enabled = !busy && !state.frozen, modifier = Modifier.heightIn(min = TrackerDimensions.minimumTouchTarget)) {
                Text(stringResource(R.string.details_cancel))
            } })
        DetailsAction.Archive -> {
            val active = state.data?.subscription?.state == SubscriptionState.ACTIVE
            AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("details_dialog").semantics { paneTitle = title },
                shape = TrackerShapes.card, containerColor = TrackerDesign.colors.surfaceRaised, tonalElevation = 0.dp,
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    Text(stringResource(if (active) R.string.details_archive_active else R.string.details_archive_cancelled))
                    if (state.frozen) Text(stringResource(R.string.details_retry_same))
                    DetailsReload(state, onRefresh)
                    if (busy) CircularProgressIndicator()
                } },
                confirmButton = { TrackerPrimaryButton(stringResource(if (state.frozen) R.string.details_retry else R.string.details_confirm),
                    onSubmit, Modifier.testTag("details_submit"), enabled = !busy && !state.conflicted) },
                dismissButton = { TextButton(onClick = onDismiss, enabled = !busy && !state.frozen, modifier = Modifier.heightIn(min = TrackerDimensions.minimumTouchTarget)) {
                    Text(stringResource(R.string.details_cancel))
                } })
        }
        else -> DetailsForm(state, title, busy, onChange, onSubmit, onDismiss, onRefresh, onFrequency, onFrequencyMode)
    }
}

@Composable
private fun DetailsReload(state: DetailsUiState, onRefresh: () -> Unit) {
    if (state.conflicted || state.frozen) {
        TextButton(onClick = onRefresh, enabled = state.phase != DetailsPhase.OperationInProgress,
            modifier = Modifier.heightIn(min = TrackerDimensions.minimumTouchTarget).testTag("details_reload")) {
            Text(stringResource(R.string.editor_reload))
        }
    }
}

@Composable
private fun DetailsForm(state: DetailsUiState, title: String, busy: Boolean, onChange: (String, String) -> Unit,
    onSubmit: () -> Unit, onDismiss: () -> Unit, onRefresh: () -> Unit,
    onFrequency: (String, String) -> Unit, onFrequencyMode: (Boolean) -> Unit) {
    val action = requireNotNull(state.action)
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val locked = busy || state.frozen
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        dismissOnBackPress = !locked, dismissOnClickOutside = false,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val focus = LocalFocusManager.current
        val scroll = rememberScrollState()
        val openPicker: (String) -> Unit = { key ->
            focus.clearFocus()
            picker = key
        }
        Box(Modifier.fillMaxSize().testTag("details_dialog").semantics { paneTitle = title }) {
            Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f))
                .clickable(enabled = !locked, interactionSource = remember { MutableInteractionSource() },
                    indication = null, onClick = onDismiss)
                .clearAndSetSemantics {})
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.92f),
                    color = TrackerDesign.colors.surfaceRaised, contentColor = TrackerDesign.colors.textPrimary,
                    shape = TrackerShapes.card, tonalElevation = 0.dp) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        // Leave the footer pinned where there is room. In a short IME viewport
                        // the fields and both actions share the same scroll container.
                        val compact = maxHeight < 220.dp
                        Column(Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(scroll) else Modifier)) {
                            val form = if (compact) Modifier else Modifier.weight(1f).verticalScroll(scroll)
                            Column(form.fillMaxWidth().padding(TrackerDimensions.modalPadding).testTag("details_action_form"),
                                verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
                                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                                state.error?.takeUnless { it == R.string.details_anchor_invalid }?.let {
                                    Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                }
                                if (state.frozen && state.error != R.string.details_retry_same) Text(stringResource(R.string.details_retry_same))
                                DetailsReload(state, onRefresh)
                                when (action) {
                                    DetailsAction.Disable -> {
                                        Text(stringResource(R.string.details_disable_explanation))
                                        DateField("end", R.string.editor_end, state, locked, onChange) { openPicker("end") }
                                    }
                                    DetailsAction.EditEnd -> DateField("end", R.string.editor_end, state, locked, onChange) { openPicker("end") }
                                    DetailsAction.Reactivate -> ReactivateFields(state, locked, onChange, onFrequency, onFrequencyMode, openPicker)
                                    else -> Unit
                                }
                                if (busy) CircularProgressIndicator()
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = TrackerDimensions.modalPadding)
                                .padding(top = TrackerDimensions.itemSpacing, bottom = TrackerDimensions.modalPadding),
                                verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
                                val confirm = when {
                                    state.frozen -> R.string.details_retry
                                    action == DetailsAction.Reactivate -> R.string.editor_save
                                    else -> R.string.details_confirm
                                }
                                TrackerPrimaryButton(stringResource(confirm), onSubmit, Modifier.fillMaxWidth().testTag("details_submit"),
                                    enabled = !busy && !state.conflicted)
                                TrackerSecondaryButton(stringResource(R.string.details_cancel), onDismiss, Modifier.fillMaxWidth(),
                                    enabled = !locked)
                            }
                        }
                    }
                }
            }
        }
        picker?.let { key ->
            EditorDateSheet(initialDateMillis(state.fields[key]), { picker = null }) { millis ->
                onChange(key, epochDayDate(millis).toString())
            }
        }
    }
}

@Composable
private fun DateField(key: String, label: Int, state: DetailsUiState, locked: Boolean,
    onChange: (String, String) -> Unit, onPick: () -> Unit) {
    TrackerTextField(state.fields[key].orEmpty(), { onChange(key, it) }, stringResource(label), enabled = !locked,
        inputModifier = Modifier.testTag("details_field_$key"),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Text))
    val description = stringResource(R.string.editor_pick_date_for, stringResource(label))
    TrackerSecondaryButton(stringResource(R.string.editor_pick_date), onPick,
        Modifier.testTag("details_date_picker_$key").semantics { contentDescription = description }, enabled = !locked)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactivateFields(state: DetailsUiState, locked: Boolean, onChange: (String, String) -> Unit,
    onFrequency: (String, String) -> Unit, onFrequencyMode: (Boolean) -> Unit, onPick: (String) -> Unit) {
    TrackerTextField(state.fields["amount"].orEmpty(), { onChange("amount", it) }, stringResource(R.string.editor_amount),
        enabled = !locked, inputModifier = Modifier.testTag("details_field_amount"),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
        suffix = { Text(stringResource(R.string.editor_ruble_suffix)) })
    Text(stringResource(R.string.editor_frequency), style = MaterialTheme.typography.labelLarge)
    val preset = editorFrequencySelection(state.fields["unit"], state.fields["count"], state.customFrequency)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing),
        verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
        EditorPeriodPreset.entries.forEach { choice ->
            TrackerSelectedCapsule(stringResource(choice.label), preset == choice, {
                if (choice == EditorPeriodPreset.CUSTOM) {
                    if (preset != EditorPeriodPreset.CUSTOM) {
                        onFrequencyMode(true)
                        onFrequency(choice.unit, choice.count)
                    }
                } else {
                    onFrequencyMode(false)
                    onFrequency(choice.unit, choice.count)
                }
            }, Modifier.testTag(choice.tag), enabled = !locked)
        }
    }
    if (preset == EditorPeriodPreset.CUSTOM) {
        TrackerTextField(state.fields["count"].orEmpty(), {
            onFrequencyMode(true)
            onChange("count", it)
        }, stringResource(R.string.editor_custom_count), enabled = !locked, inputModifier = Modifier.testTag("details_field_count"),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing),
            verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
            listOf("DAY" to R.string.editor_unit_day, "WEEK" to R.string.editor_unit_week,
                "MONTH" to R.string.editor_unit_month, "YEAR" to R.string.editor_unit_year).forEach { (unit, label) ->
                TrackerSelectedCapsule(stringResource(label), state.fields["unit"] == unit, {
                    onFrequencyMode(true)
                    onChange("unit", unit)
                }, Modifier.testTag("frequency_unit_$unit"), enabled = !locked)
            }
        }
    }
    DateField("date", R.string.details_next_renewal, state, locked, onChange, { onPick("date") })
    Text(stringResource(R.string.editor_series), color = TrackerDesign.colors.textSecondary)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val anchorError = state.error == R.string.details_anchor_invalid
    val showAnchor = expanded || anchorError
    val expandedState = stringResource(if (showAnchor) R.string.ui_expanded else R.string.ui_collapsed)
    TrackerSecondaryButton(stringResource(R.string.editor_extra), { expanded = !showAnchor },
        Modifier.testTag("details_extra").semantics { stateDescription = expandedState }, enabled = !locked && !anchorError)
    if (showAnchor) {
        val requester = remember { BringIntoViewRequester() }
        LaunchedEffect(anchorError) {
            if (anchorError) {
                expanded = true
                requester.bringIntoView()
            }
        }
        TrackerTextField(state.fields["anchorDay"].orEmpty(), { onChange("anchorDay", it) },
            stringResource(R.string.editor_anchor_day), modifier = Modifier.bringIntoViewRequester(requester),
            errorMessage = if (anchorError) stringResource(R.string.details_anchor_invalid) else null,
            enabled = !locked, inputModifier = Modifier.testTag("details_field_anchorDay"),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
    }
}
