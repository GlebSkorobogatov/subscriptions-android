package com.glebskorobogatov.subscriptiontracker.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.integration.PaymentHistoryItem
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.PaymentSource
import com.glebskorobogatov.subscriptiontracker.domain.model.Money
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.util.UUID

@Composable
fun HistoryScreen(vm: HistoryViewModel, exitRequest: Int, onBack: () -> Unit, onMissing: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Presentation context only. The ViewModel continues owning the authoritative expected snapshot.
    var editedRefundId by rememberSaveable { mutableStateOf<String?>(null) }
    fun leave() {
        if(state.phase == HistoryPhase.OperationInProgress || state.frozen) return
        if(state.action != null) vm.dismiss() else if(state.selected != null) vm.select(null) else onBack()
    }
    BackHandler { leave() }
    var lastExit by remember { mutableIntStateOf(exitRequest) }
    LaunchedEffect(exitRequest) { if(lastExit != exitRequest) { lastExit = exitRequest; leave() } }
    HistoryPane(state,::leave,onMissing,vm::refresh,vm::select) { action,payment,refund ->
        editedRefundId = refund?.toString()
        vm.open(action,payment,refund)
    }
    if(state.action != null) HistoryDialog(state,vm::change,vm::submit,vm::dismiss,vm::refresh,editedRefundId?.let(UUID::fromString))
}

/** Pure read-only presentation; all mutations are still commands in the existing ViewModel. */
@Composable
internal fun HistoryPane(state: HistoryUiState, onBack: () -> Unit, onMissing: () -> Unit, onRefresh: () -> Unit,
    onSelect: (UUID?) -> Unit, onAction: (HistoryAction,UUID?,UUID?) -> Unit) {
    val busy = state.phase == HistoryPhase.OperationInProgress
    val enabled = !busy && state.action == null
    val item = state.data?.history?.find { it.payment.id == state.selected }
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(lineHeightStyle =
        LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None))) {
        Column(Modifier.fillMaxSize().imePadding().testTag("history_screen")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = TrackerDimensions.screenPadding,vertical = 12.dp),verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack,enabled = !busy && !state.frozen) { Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.navigate_back)) }
                Text(stringResource(if(state.selected == null) R.string.history_title else R.string.history_payment_title),
                    Modifier.weight(1f),style = MaterialTheme.typography.titleLarge)
            }
            state.error?.let { Text(stringResource(it),color = TrackerDesign.colors.error,modifier = Modifier.padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
            when(state.phase) {
                HistoryPhase.Loading -> Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.history_loading))
                }
                HistoryPhase.Error -> TrackerSecondaryButton(stringResource(R.string.retry_loading),onRefresh,Modifier.padding(20.dp))
                HistoryPhase.NotFound -> Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.subscription_not_found))
                    TrackerSecondaryButton(stringResource(R.string.return_to_subscriptions),onMissing)
                }
                else -> if(state.selected == null) {
                    LazyColumn(Modifier.fillMaxSize().testTag("history_list"),contentPadding = PaddingValues(horizontal = TrackerDimensions.screenPadding,vertical = 12.dp)) {
                        item { Text(state.data?.subscription?.name.orEmpty(),style = MaterialTheme.typography.titleMedium) }
                        item { Text(stringResource(R.string.history_facts_only),Modifier.padding(vertical = 12.dp),color = TrackerDesign.colors.textSecondary) }
                        if(!state.data?.history.isNullOrEmpty()) item { HistorySummary(state.data!!.history) }
                        item { TrackerPrimaryButton(stringResource(R.string.history_add_payment),{ onAction(HistoryAction.AddPayment,null,null) },
                            Modifier.padding(vertical = 16.dp).testTag("history_add_payment"),enabled) }
                        if(state.data?.history.isNullOrEmpty()) item { Text(stringResource(R.string.history_empty),Modifier.padding(vertical = 20.dp),color = TrackerDesign.colors.textSecondary) }
                        items(historyNewestFirst(state.data?.history.orEmpty()),key = { it.payment.id.toString() }) { payment ->
                            Column(Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(payment.payment.id) }
                                .testTag("history_payment_${payment.payment.id}").padding(vertical = 16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PaymentInfo(payment)
                            }
                            HorizontalDivider(color = TrackerDesign.colors.divider)
                        }
                        item { TrackerSecondaryButton(stringResource(R.string.navigate_back),onBack,enabled = !busy && !state.frozen) }
                    }
                } else if(item == null) {
                    Column(Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.history_payment_missing))
                        TrackerSecondaryButton(stringResource(R.string.history_back_list),{ onSelect(null) })
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TrackerDimensions.screenPadding)
                        .testTag("history_payment_details"),verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(state.data?.subscription?.name.orEmpty(),style = MaterialTheme.typography.titleMedium)
                        PaymentInfo(item,showRefunds = false,hero = true)
                        PaymentContext(item)
                        OperationMenu(stringResource(R.string.history_payment_menu),enabled,
                            stringResource(R.string.history_edit_payment),{ onAction(HistoryAction.EditPayment,item.payment.id,null) },
                            stringResource(R.string.history_delete_payment),{ onAction(HistoryAction.DeletePayment,item.payment.id,null) })
                        HorizontalDivider(color = TrackerDesign.colors.divider)
                        Text(stringResource(R.string.history_refunds),style = MaterialTheme.typography.titleMedium,modifier = Modifier.semantics { heading() })
                        TrackerSecondaryButton(stringResource(R.string.history_add_refund),{ onAction(HistoryAction.AddRefund,item.payment.id,null) },enabled = enabled)
                        if(item.refunds.isEmpty()) Text(stringResource(R.string.history_no_refunds),color = TrackerDesign.colors.textSecondary)
                        refundsNewestFirst(item.refunds).forEach { refund ->
                            Column(Modifier.fillMaxWidth().testTag("history_refund_${refund.id}"),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.history_refund_amount,Money(refund.amountMinor,refund.currencyCode).format(),refund.currencyCode.value),style = MaterialTheme.typography.titleMedium)
                                Text(refund.refundedOn.toString(),color = TrackerDesign.colors.textSecondary)
                                OperationMenu(stringResource(R.string.history_refund_menu,Money(refund.amountMinor,refund.currencyCode).format(),refund.currencyCode.value,refund.refundedOn.toString()),enabled,
                                    stringResource(R.string.history_edit_refund),{ onAction(HistoryAction.EditRefund,item.payment.id,refund.id) },
                                    stringResource(R.string.history_delete_refund),{ onAction(HistoryAction.DeleteRefund,item.payment.id,refund.id) },
                                    editDescription = stringResource(R.string.refund_edit_action,Money(refund.amountMinor,refund.currencyCode).format(),refund.currencyCode.value,refund.refundedOn.toString()),
                                    deleteDescription = stringResource(R.string.refund_delete_action,Money(refund.amountMinor,refund.currencyCode).format(),refund.currencyCode.value,refund.refundedOn.toString()))
                                HorizontalDivider(color = TrackerDesign.colors.divider)
                            }
                        }
                        TrackerSecondaryButton(stringResource(R.string.history_back_list),onBack,enabled = !busy && !state.frozen)
                    }
                }
            }
        }
    }
}

/** Display aggregation only; Money enforces same-currency arithmetic and checked Long overflow. */
internal fun historyNetTotals(items: List<PaymentHistoryItem>): List<Money> = items.groupBy { it.netExpense.currency }.entries
    .sortedBy { it.key.value }.map { (currency,rows) -> rows.fold(Money(0,currency)) { sum,row -> sum + row.netExpense } }

@Composable private fun HistorySummary(items: List<PaymentHistoryItem>) {
    val totals = remember(items) { runCatching { historyNetTotals(items) } }
    TrackerSummaryCard(Modifier.fillMaxWidth().testTag("history_summary")) {
        Text(stringResource(R.string.history_summary_title),style = MaterialTheme.typography.titleSmall)
        totals.fold(onSuccess = { amounts -> amounts.forEach { Text(stringResource(R.string.money_amount,it.format(),it.currency.value),style = MaterialTheme.typography.titleLarge) } },
            onFailure = { Text(stringResource(R.string.history_summary_error),color = TrackerDesign.colors.error,modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) })
    }
}

@Composable private fun OperationMenu(description: String,enabled: Boolean,edit: String,onEdit: () -> Unit,delete: String,onDelete: () -> Unit,editDescription: String? = null,deleteDescription: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true },enabled = enabled,modifier = Modifier.semantics { contentDescription = description }) { Icon(Icons.Default.MoreVert,null) }
        DropdownMenu(expanded && enabled,{ expanded = false }) {
            DropdownMenuItem(text = { Text(edit) },modifier = Modifier.semantics { editDescription?.let { contentDescription = it } },onClick = { expanded = false; onEdit() })
            DropdownMenuItem(text = { Text(delete,color = TrackerDesign.colors.error) },modifier = Modifier.semantics { deleteDescription?.let { contentDescription = it } },onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable private fun PaymentInfo(item: PaymentHistoryItem,showRefunds: Boolean = true,hero: Boolean = false) {
    val payment = item.payment
    Text(stringResource(R.string.history_date_value,payment.paidOn.toString()),style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.money_amount,Money(payment.amountMinor,payment.currencyCode).format(),payment.currencyCode.value),
        style = if(hero) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge)
    Text(stringResource(if(payment.source == PaymentSource.MANUAL_HISTORY) R.string.history_manual else R.string.history_renewal),color = TrackerDesign.colors.textSecondary)
    if(item.refunds.isNotEmpty()) Text(stringResource(if(item.netExpense.amountMinor == 0L) R.string.history_full_refund else R.string.history_partial_refund))
    if(showRefunds) refundsNewestFirst(item.refunds).forEach { refund -> Text(stringResource(R.string.details_refund,
        refund.refundedOn.toString(),Money(refund.amountMinor,refund.currencyCode).format(),refund.currencyCode.value),color = TrackerDesign.colors.textSecondary) }
    Text(stringResource(R.string.history_net,item.netExpense.format(),item.netExpense.currency.value),modifier = Modifier.testTag("history_net"))
}

@Composable private fun PaymentContext(item: PaymentHistoryItem,editedRefundId: UUID? = null) {
    val paid = Money(item.payment.amountMinor,item.payment.currencyCode)
    Text(stringResource(R.string.history_original,paid.format(),paid.currency.value))
    val returned = paid - item.netExpense
    Text(stringResource(R.string.history_returned,returned.format(),returned.currency.value),color = TrackerDesign.colors.textSecondary)
    RefundAvailable(item,editedRefundId)
}

@Composable private fun RefundAvailable(item: PaymentHistoryItem,editedRefundId: UUID? = null) {
    // Uses the same Money arithmetic as the existing net expense; never validates or submits a command here.
    val original = item.refunds.find { it.id == editedRefundId }
    val available = if(original == null) item.netExpense else item.netExpense + Money(original.amountMinor,original.currencyCode)
    Text(stringResource(if(original == null) R.string.history_available else R.string.history_edit_available,available.format(),available.currency.value),
        Modifier.testTag("history_available"))
}

@Composable
internal fun HistoryDialog(state: HistoryUiState,onChange: (String,String) -> Unit,onSubmit: () -> Unit,
    onDismiss: () -> Unit,onRefresh: () -> Unit,editedRefundId: UUID? = null) {
    val action = requireNotNull(state.action)
    val deleting = action in setOf(HistoryAction.DeletePayment,HistoryAction.DeleteRefund)
    val refund = action in setOf(HistoryAction.AddRefund,HistoryAction.EditRefund,HistoryAction.DeleteRefund)
    val busy = state.phase in setOf(HistoryPhase.OperationInProgress,HistoryPhase.Loading)
    val title = when(action) {
        HistoryAction.AddPayment -> R.string.history_add_payment; HistoryAction.EditPayment -> R.string.history_edit_payment
        HistoryAction.DeletePayment -> R.string.history_delete_payment; HistoryAction.AddRefund -> R.string.history_add_refund
        HistoryAction.EditRefund -> R.string.history_edit_refund; HistoryAction.DeleteRefund -> R.string.history_delete_refund
    }
    val titleText = stringResource(title)
    val scrollTitle = scrollDialogHeading()
    val item = state.data?.history?.find { it.payment.id == state.selected }
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(lineHeightStyle =
        LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None))) {
        AlertDialog(onDismissRequest = onDismiss,modifier = Modifier.imePadding().testTag("history_dialog").semantics { paneTitle = titleText },shape = TrackerShapes.card,
            containerColor = TrackerDesign.colors.surfaceRaised,tonalElevation = 0.dp,title = if(scrollTitle) null else ({ Text(titleText) }),text = {
            Column(Modifier.testTag("history_action_form").verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if(scrollTitle) TrackerDialogHeading(titleText)
                state.error?.let { Text(stringResource(it),color = TrackerDesign.colors.error,modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if(state.frozen) Text(stringResource(R.string.history_retry_same))
                if(state.frozen || state.conflicted || state.phase == HistoryPhase.Error) TrackerSecondaryButton(stringResource(R.string.editor_reload),onRefresh,enabled = !busy)
                if(deleting) Text(stringResource(if(action == HistoryAction.DeletePayment) R.string.history_delete_payment_warning else R.string.history_delete_refund_warning))
                else {
                    if(action == HistoryAction.AddPayment) Text(stringResource(R.string.history_historical_explanation))
                    if(refund && item != null) {
                        Text(stringResource(R.string.history_refund_context,item.payment.paidOn.toString()),style = MaterialTheme.typography.titleSmall)
                        PaymentContext(item,if(action == HistoryAction.EditRefund) editedRefundId else null)
                    }
                    @Composable fun field(key: String,label: Int,readOnly: Boolean = false) {
                        TrackerTextField(state.fields[key].orEmpty(),{ onChange(key,it) },stringResource(label),
                            inputModifier = Modifier.testTag("history_field_$key"),enabled = !busy && !state.frozen && !state.conflicted,
                            readOnly = readOnly,errorMessage = state.errors[key]?.let { stringResource(it) },
                            supportingModifier = Modifier.testTag("history_error_$key"),
                            keyboardOptions = KeyboardOptions(keyboardType = if(key == "amount") KeyboardType.Decimal else KeyboardType.Text))
                        if(key == "amount" && key in state.errors && refund && item != null) RefundAvailable(item,if(action == HistoryAction.EditRefund) editedRefundId else null)
                    }
                    field("date",if(refund) R.string.history_refund_date else R.string.details_paid_date)
                    field("amount",R.string.history_amount)
                    field("currency",if(refund) R.string.history_refund_currency else R.string.editor_currency,readOnly = refund)
                    if(refund) Text(stringResource(R.string.history_refund_rules),color = TrackerDesign.colors.textSecondary)
                }
                if(busy) CircularProgressIndicator()
            }
        },confirmButton = {
            TrackerPrimaryButton(stringResource(if(state.frozen) R.string.history_retry else if(deleting) R.string.history_confirm_delete else R.string.editor_save),
                onSubmit,enabled = !busy && !state.conflicted && state.phase != HistoryPhase.Error)
        },dismissButton = { TrackerSecondaryButton(stringResource(R.string.details_cancel),onDismiss,enabled = !busy && !state.frozen) })
    }
}
