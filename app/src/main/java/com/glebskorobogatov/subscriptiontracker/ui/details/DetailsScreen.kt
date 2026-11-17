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
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.LocalDate
import java.util.UUID

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import com.glebskorobogatov.subscriptiontracker.ui.design.*

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
                            Text(stringResource(R.string.money_amount,RubPrice(s.priceMinor).format(),"RUB"),
                                style = MaterialTheme.typography.displayLarge,textAlign = TextAlign.Center,modifier = Modifier.fillMaxWidth())
                            Text(stringResource(when(s.intervalUnit) {
                                FrequencyUnit.DAY -> R.string.frequency_days; FrequencyUnit.WEEK -> R.string.frequency_weeks
                                FrequencyUnit.MONTH -> R.string.frequency_months; FrequencyUnit.YEAR -> R.string.frequency_years
                            },s.intervalCount),color = TrackerDesign.colors.textSecondary)
                        }
                        val status = when(s.state) {
                            SubscriptionState.ACTIVE -> R.string.details_active
                            SubscriptionState.CANCELLED -> R.string.status_cancelled
                            SubscriptionState.ARCHIVED -> R.string.status_archived
                        }
                        val event = detail.period.due
                        if (detail.calendarError) Text(stringResource(R.string.projection_calendar_error),
                            modifier = Modifier.testTag("details_calendar_error"),color = TrackerDesign.colors.error)
                        val days = detail.period.remainingDays
                        TrackerSummaryCard(Modifier.fillMaxWidth()) {
                            TrackerStatusLabel(stringResource(status),secondary = s.state != SubscriptionState.ACTIVE,style = MaterialTheme.typography.titleMedium)
                            if(event != null) {
                                Text(stringResource(if(s.state == SubscriptionState.CANCELLED) R.string.access_until else R.string.expected_on,event.toString()))
                                if(days == 0L) Text(stringResource(R.string.event_today))
                                else if(days != null && days > 0) Text(stringResource(R.string.days_remaining,days))
                            } else Text(stringResource(if(s.state == SubscriptionState.CANCELLED) R.string.access_unknown else R.string.event_unknown),
                                color = TrackerDesign.colors.textSecondary)
                            TrackerPeriodIndicator(detail.period.remainingFraction,days,s.state,
                                stringResource(R.string.progress_status,stringResource(status)),
                                modifier = Modifier.testTag("details_progress_track"),
                                indicatorModifier = Modifier.testTag("details_progress"))
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
                            if(s.state == SubscriptionState.ACTIVE) action(R.string.details_disable,DetailsAction.Disable)
                            if(s.state == SubscriptionState.CANCELLED) action(R.string.details_edit_end,DetailsAction.EditEnd)
                            if(s.state != SubscriptionState.ARCHIVED) action(R.string.details_archive,DetailsAction.Archive)
                            if(s.state != SubscriptionState.ACTIVE) action(R.string.details_reactivate,DetailsAction.Reactivate)
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
            DetailsAction.EditEnd -> R.string.details_edit_end; DetailsAction.Disable -> R.string.details_disable
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
                    DetailsAction.EditEnd -> { field("end",R.string.editor_end) }
                    DetailsAction.Disable -> { Text(stringResource(R.string.details_disable_explanation)); field("end",R.string.editor_end) }
                    DetailsAction.Reactivate -> {
                        field("date",R.string.details_next_date); field("amount",R.string.editor_amount)
                        state.data?.subscription?.let { Text(stringResource(R.string.editor_price_currency,"RUB")) }
                        field("count",R.string.editor_count)
                        field("anchorDay",R.string.editor_anchor_day)
                        Column { listOf("DAY" to R.string.editor_days,"WEEK" to R.string.editor_weeks,"MONTH" to R.string.editor_months,"YEAR" to R.string.editor_year).forEach { (unit,label) ->
                            FilterChip(state.fields["unit"] == unit,{ onChange("unit",unit) },label = { Text(stringResource(label)) },enabled = !busy)
                        } }
                    }
                    DetailsAction.Delete -> Text(stringResource(R.string.details_delete_warning))
                    DetailsAction.Archive -> Text(stringResource(R.string.details_archive_warning))
                }
                if(busy) CircularProgressIndicator()
            }
        }, confirmButton = { TrackerPrimaryButton(stringResource(if(state.frozen) R.string.details_retry else R.string.details_confirm),onSubmit,enabled = !busy && !state.conflicted) },
        dismissButton = { TextButton(onClick = onDismiss,enabled = !busy && !state.frozen) { Text(stringResource(R.string.details_cancel)) } })
}

}
