package com.glebskorobogatov.subscriptiontracker.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import com.glebskorobogatov.subscriptiontracker.data.room.ReminderEventType
import com.glebskorobogatov.subscriptiontracker.data.room.ReminderMode

@Composable
fun DetailsScreen(vm: DetailsViewModel, id: UUID, exitRequest: Int, onBack: () -> Unit, onEdit: () -> Unit, onDeleted: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    fun leave() { if (state.phase != DetailsPhase.OperationInProgress && !state.frozen) onBack() }
    BackHandler { if (state.action != null) vm.dismiss() else leave() }
    var lastExit by remember { mutableIntStateOf(exitRequest) }
    LaunchedEffect(exitRequest) { if (lastExit != exitRequest) { lastExit = exitRequest; leave() } }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    DetailsPane(state, id, { leave() }, onEdit, onDeleted, vm::open, vm::refresh)
    if (state.action != null) DetailsActionDialog(state, vm::change, vm::submit, vm::dismiss, vm::refresh)
}

/** Read-only presentation adapter; callbacks still enter the existing ViewModel commands. */
@Composable
internal fun DetailsPane(state: DetailsUiState, id: UUID, onBack: () -> Unit, onEdit: () -> Unit,
    onDeleted: () -> Unit, onAction: (DetailsAction) -> Unit, onRefresh: () -> Unit) {
    val enabled = state.phase != DetailsPhase.OperationInProgress && state.action == null
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))) {
        Column(Modifier.fillMaxSize().testTag("details_$id")) {
            DetailsHeader(onBack, onEdit, state.phase != DetailsPhase.OperationInProgress && !state.frozen,
                enabled && state.phase == DetailsPhase.Content, state.data != null && (state.phase == DetailsPhase.Content || state.phase == DetailsPhase.OperationInProgress))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(TrackerDimensions.screenPadding)
                .testTag("details_content"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.error?.let { Text(stringResource(it),color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                when (state.phase) {
                    DetailsPhase.Loading -> Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.details_loading))
                    }
                    DetailsPhase.Error -> TrackerPrimaryButton(stringResource(R.string.retry_loading), onRefresh)
                    DetailsPhase.NotFound -> Text(stringResource(R.string.subscription_not_found))
                    else -> state.data?.let { detail ->
                        val s = detail.subscription
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SubscriptionImage(s.name,s.logoAssetId,catalogId = s.catalogLogoId)
                            Text(s.name,style = MaterialTheme.typography.headlineSmall,textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().semantics { heading() })
                            s.accountLabel?.takeIf(String::isNotBlank)?.let { Text(it,color = TrackerDesign.colors.textSecondary,textAlign = TextAlign.Center) }
                            Text(stringResource(R.string.details_plan_price),style = MaterialTheme.typography.bodySmall,color = TrackerDesign.colors.textSecondary)
                            Text(stringResource(R.string.money_amount,Money(s.priceMinor,s.currencyCode).format(),s.currencyCode.value),
                                style = MaterialTheme.typography.displayLarge,textAlign = TextAlign.Center,modifier = Modifier.fillMaxWidth())
                            Text(stringResource(when(s.intervalUnit) {
                                FrequencyUnit.DAY -> R.string.frequency_days; FrequencyUnit.WEEK -> R.string.frequency_weeks
                                FrequencyUnit.MONTH -> R.string.frequency_months; FrequencyUnit.YEAR -> R.string.frequency_years
                            },s.intervalCount),color = TrackerDesign.colors.textSecondary)
                        }
                        val status = when(detail.lifecycle.status) {
                            VisibleStatus.ACTIVE -> R.string.details_active; VisibleStatus.AWAITING_RENEWAL -> R.string.status_awaiting
                            VisibleStatus.PAYMENT_ISSUE -> R.string.status_payment_issue; VisibleStatus.CANCELLATION_SCHEDULED -> R.string.status_cancelled
                            VisibleStatus.ARCHIVED -> R.string.status_archived
                        }
                        val event = if(s.lifecycleState == LifecycleState.CANCELLATION_SCHEDULED) s.paidAccessEndDate else detail.lifecycle.expectedDate
                        val days = event?.let { ChronoUnit.DAYS.between(state.today,it) }
                        TrackerSummaryCard(Modifier.fillMaxWidth()) {
                            Text(stringResource(status),style = MaterialTheme.typography.titleMedium)
                            if(event != null) {
                                Text(stringResource(if(s.lifecycleState == LifecycleState.CANCELLATION_SCHEDULED) R.string.access_until else R.string.expected_on,event.toString()))
                                if(days == 0L) Text(stringResource(R.string.event_today))
                                else if(days != null && days > 0) Text(stringResource(R.string.days_remaining,days))
                            } else Text(stringResource(if(s.lifecycleState == LifecycleState.CANCELLATION_SCHEDULED) R.string.access_unknown else R.string.event_unknown),
                                color = TrackerDesign.colors.textSecondary)
                            if(detail.lifecycle.status == VisibleStatus.AWAITING_RENEWAL) Text(stringResource(R.string.awaiting_explanation))
                            if(detail.lifecycle.status == VisibleStatus.PAYMENT_ISSUE) Text(stringResource(R.string.payment_issue_explanation))
                            val fraction = if(detail.lifecycle.status == VisibleStatus.AWAITING_RENEWAL) 0f else detail.lifecycle.remainingFraction?.toFloat()
                            fraction?.let {
                                val progressText = stringResource(R.string.progress_status,stringResource(if(detail.lifecycle.status == VisibleStatus.AWAITING_RENEWAL) R.string.status_awaiting else if(detail.lifecycle.status == VisibleStatus.CANCELLATION_SCHEDULED) R.string.status_cancelled else R.string.details_active))
                                val color = when { days != null && days <= 1 -> TrackerDesign.colors.statusUrgent; days != null && days <= 3 -> TrackerDesign.colors.statusSoon; else -> TrackerDesign.colors.statusCalm }
                                Box(Modifier.fillMaxWidth().height(TrackerDimensions.progressHeight).testTag("details_progress_track")) {
                                    LinearProgressIndicator(progress = { it },modifier = Modifier.fillMaxSize().testTag("details_progress").semantics { stateDescription = progressText },
                                        color = color,trackColor = TrackerDesign.colors.divider,gapSize = 0.dp,drawStopIndicator = {},strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                                }
                            }
                        }
                        TrackerListRow(stringResource(R.string.details_renewal_label),supportingText = stringResource(if(s.renewalMode == RenewalMode.AUTO) R.string.status_auto else R.string.status_manual))
                        // Cancelled access is already the main event above; display a stored end only once.
                        s.paidAccessEndDate?.takeIf { s.lifecycleState != LifecycleState.CANCELLATION_SCHEDULED }?.let {
                            TrackerListRow(stringResource(R.string.access_until,it.toString()))
                        }
                        s.cancellationLeadDays?.let { lead ->
                            TrackerListRow(stringResource(R.string.details_cancellation_lead,lead),supportingText = detail.lifecycle.expectedDate?.let { date ->
                                runCatching { date.minusDays(lead.toLong()) }.getOrNull()?.let { stringResource(R.string.details_cancellation_date,it.toString()) }
                            })
                        }
                        listOf(ReminderEventType.PAYMENT to R.string.editor_payment_rules,ReminderEventType.CANCELLATION to R.string.editor_cancel_rules).forEach { (type,label) ->
                            val rule = detail.reminders.firstOrNull { it.eventType == type }
                            val summary = when {
                                rule == null || rule.mode == ReminderMode.INHERIT_GLOBAL -> stringResource(R.string.editor_inherit)
                                rule.offsets.isEmpty() -> stringResource(R.string.settings_offsets_empty)
                                else -> stringResource(R.string.details_reminder_offsets,rule.offsets.sortedDescending().joinToString(", ") { it.toString() })
                            }
                            TrackerListRow(stringResource(label),supportingText = summary)
                        }
                        var management by rememberSaveable { mutableStateOf(false) }
                        val managementState = stringResource(if(management) R.string.ui_expanded else R.string.ui_collapsed)
                        TrackerListRow(stringResource(R.string.details_management),Modifier.testTag("details_management").semantics { stateDescription = managementState },
                            supportingText = stringResource(R.string.details_management_note),onClick = if(enabled) ({ management = !management }) else null)
                        if(management) {
                            @Composable fun action(label: Int, action: DetailsAction) {
                                TextButton(onClick = { onAction(action) },enabled = enabled,modifier = Modifier.fillMaxWidth().heightIn(min = TrackerDimensions.minimumTouchTarget),
                                    colors = ButtonDefaults.textButtonColors(contentColor = if(action == DetailsAction.Delete) MaterialTheme.colorScheme.error else TrackerDesign.colors.textPrimary)) {
                                    Text(stringResource(label),Modifier.fillMaxWidth(),textAlign = TextAlign.Start)
                                }
                            }
                            if(s.lifecycleState == LifecycleState.ACTIVE || s.lifecycleState == LifecycleState.PAYMENT_ISSUE) {
                                if(s.lifecycleState == LifecycleState.PAYMENT_ISSUE) action(R.string.details_resolve_date,DetailsAction.ResolveDate)
                                action(R.string.details_disable,DetailsAction.Disable)
                            }
                            if(s.lifecycleState != LifecycleState.ARCHIVED) action(R.string.details_archive,DetailsAction.Archive)
                            else action(R.string.details_reactivate,DetailsAction.Reactivate)
                            action(R.string.details_delete,DetailsAction.Delete)
                        }
                        if(state.phase == DetailsPhase.OperationInProgress) CircularProgressIndicator()
                        TrackerSecondaryButton(stringResource(R.string.editor_reload),onRefresh,enabled = enabled)
                    }
                }
                TrackerSecondaryButton(stringResource(if(state.phase == DetailsPhase.NotFound) R.string.return_to_subscriptions else R.string.navigate_back),
                    { if(state.phase == DetailsPhase.NotFound) onDeleted() else onBack() },
                    enabled = state.phase != DetailsPhase.OperationInProgress && !state.frozen)
            }
        }
    }
}

@Composable private fun DetailsHeader(onBack: () -> Unit,onEdit: () -> Unit,backEnabled: Boolean,editEnabled: Boolean,showEdit: Boolean) {
    val stacked = LocalDensity.current.fontScale >= 1.5f
    Column(Modifier.fillMaxWidth().padding(horizontal = TrackerDimensions.screenPadding,vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack,enabled = backEnabled) { Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.navigate_back)) }
            Text(stringResource(R.string.details_title),Modifier.weight(1f),style = MaterialTheme.typography.titleLarge)
            if(showEdit && !stacked) TrackerSecondaryButton(stringResource(R.string.editor_edit),onEdit,enabled = editEnabled)
        }
        if(showEdit && stacked) TrackerSecondaryButton(stringResource(R.string.editor_edit),onEdit,Modifier.align(Alignment.End),editEnabled)
    }
}

@Composable
internal fun DetailsActionDialog(state: DetailsUiState, onChange: (String, String) -> Unit,
    onSubmit: () -> Unit, onDismiss: () -> Unit, onRefresh: () -> Unit) {
    val action = requireNotNull(state.action)
    val busy = state.phase == DetailsPhase.OperationInProgress
    val title = stringResource(when(action) {
            DetailsAction.ResolveDate -> R.string.details_resolve_date; DetailsAction.Disable -> R.string.details_disable
            DetailsAction.Archive -> R.string.details_archive; DetailsAction.Reactivate -> R.string.details_reactivate; DetailsAction.Delete -> R.string.details_delete
        })
    val scrollTitle = scrollDialogHeading()
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(lineHeightStyle =
        LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None))) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.imePadding().testTag("details_dialog").semantics { paneTitle = title },
        shape = TrackerShapes.card, containerColor = TrackerDesign.colors.surfaceRaised, tonalElevation = 0.dp,
        title = if(scrollTitle) null else ({ Text(title) }), text = {
            Column(Modifier.testTag("details_action_form").verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if(scrollTitle) TrackerDialogHeading(title)
                state.error?.let { Text(stringResource(it),color = MaterialTheme.colorScheme.error,modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if(state.frozen) Text(stringResource(R.string.details_retry_same))
                if(state.conflicted || state.frozen) TextButton(onClick = onRefresh) { Text(stringResource(R.string.editor_reload)) }
                @Composable fun field(key: String,label: Int) {
                    TrackerTextField(state.fields[key].orEmpty(),{ onChange(key,it) },stringResource(label),
                        enabled = !busy && !state.frozen, inputModifier = Modifier.testTag("details_field_$key"),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType =
                            if(key == "amount") androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text))
                }
                when(action) {
                    DetailsAction.ResolveDate -> { field("date",R.string.details_next_date); Text(stringResource(R.string.details_no_payment)) }
                    DetailsAction.Disable -> { Text(stringResource(R.string.details_disable_explanation)); field("end",R.string.editor_end) }
                    DetailsAction.Reactivate -> {
                        field("date",R.string.details_next_date); field("amount",R.string.editor_amount); field("currency",R.string.editor_currency)
                        field("count",R.string.editor_count)
                        Column { listOf("DAY" to R.string.editor_days,"WEEK" to R.string.editor_weeks,"MONTH" to R.string.editor_months,"YEAR" to R.string.editor_year).forEach { (unit,label) ->
                            FilterChip(state.fields["unit"] == unit,{ onChange("unit",unit) },label = { Text(stringResource(label)) },enabled = !busy)
                        } }
                        Column { listOf("AUTO" to R.string.status_auto,"MANUAL" to R.string.status_manual).forEach { (mode,label) ->
                            FilterChip(state.fields["mode"] == mode,{ onChange("mode",mode) },label = { Text(stringResource(label)) },enabled = !busy)
                        } }
                    }
                    DetailsAction.Delete -> Text(stringResource(R.string.details_delete_warning))
                    DetailsAction.Archive -> Text(stringResource(R.string.details_archive_warning))
                }
                if(action == DetailsAction.ResolveDate) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(state.fields["reanchor"] == "true",
                        enabled = !busy && !state.frozen,role = Role.Checkbox,onValueChange = { onChange("reanchor",it.toString()) })) {
                        Checkbox(state.fields["reanchor"] == "true",onCheckedChange = null,enabled = !busy && !state.frozen)
                        Text(stringResource(R.string.details_reanchor),modifier = Modifier.weight(1f))
                    }
                }
                if(busy) CircularProgressIndicator()
            }
        }, confirmButton = { TrackerPrimaryButton(stringResource(if(state.frozen) R.string.details_retry else R.string.details_confirm),onSubmit,enabled = !busy && !state.conflicted) },
        dismissButton = { TextButton(onClick = onDismiss,enabled = !busy && !state.frozen) { Text(stringResource(R.string.details_cancel)) } })
}

}
