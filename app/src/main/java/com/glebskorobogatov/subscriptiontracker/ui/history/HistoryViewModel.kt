package com.glebskorobogatov.subscriptiontracker.ui.history

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionDetails
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.payment.PaymentConflict
import com.glebskorobogatov.subscriptiontracker.domain.refund.HistoryConflict
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class HistoryPhase { Loading, Empty, Content, NotFound, Error, OperationInProgress }
enum class HistoryAction { AddPayment, EditPayment, DeletePayment, AddRefund, EditRefund, DeleteRefund }
data class HistoryUiState(val phase: HistoryPhase = HistoryPhase.Loading, val data: SubscriptionDetails? = null,
    val selected: UUID? = null, val action: HistoryAction? = null, val fields: Map<String,String> = emptyMap(),
    val errors: Map<String,Int> = emptyMap(), val error: Int? = null, val frozen: Boolean = false, val conflicted: Boolean = false)

class HistoryViewModel(private val saved: SavedStateHandle, private val owner: SubscriptionsViewModel,
    private val subscriptionId: UUID) : ViewModel() {
    private val mutable = MutableStateFlow(HistoryUiState(selected = saved.get<String>("selected")?.let(UUID::fromString),
        action = saved.get<String>("action")?.let(HistoryAction::valueOf), fields = saved.get<HashMap<String,String>>("fields") ?: emptyMap(),
        frozen = saved["sent"] ?: false, conflicted = saved["conflicted"] ?: false))
    val state = mutable.asStateFlow()
    private var reader: Job? = null
    private var operation: Job? = null
    init { refresh() }
    override fun onCleared() { reader?.cancel(); operation?.cancel() }
    private fun phase(data: SubscriptionDetails?) = when { data == null -> HistoryPhase.NotFound; data.history.isEmpty() -> HistoryPhase.Empty; else -> HistoryPhase.Content }
    fun refresh() {
        if(state.value.phase == HistoryPhase.OperationInProgress) return
        if(state.value.conflicted) clearDraft()
        if(owner.state.value is SubscriptionsUiState.Error) owner.retry()
        reader?.cancel()
        reader = owner.viewModelScope.launch {
            mutable.value = state.value.copy(phase = HistoryPhase.Loading,error = null)
            try {
                owner.state.first { it !is SubscriptionsUiState.Loading }
                owner.details(subscriptionId).collect { data ->
                    mutable.value = state.value.copy(data = data,phase = if(state.value.phase == HistoryPhase.OperationInProgress) HistoryPhase.OperationInProgress else phase(data))
                    if(data == null && state.value.phase != HistoryPhase.OperationInProgress) clearDraft()
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { mutable.value = state.value.copy(phase = HistoryPhase.Error,error = R.string.history_load_error) }
        }
    }
    fun select(id: UUID?) { if(state.value.action != null) return; saved["selected"] = id?.toString(); mutable.value = state.value.copy(selected = id) }
    fun open(action: HistoryAction, paymentId: UUID? = null, refundId: UUID? = null) {
        if(state.value.phase !in setOf(HistoryPhase.Empty,HistoryPhase.Content) || state.value.action != null) return
        val data = state.value.data ?: return
        val item = paymentId?.let { id -> data.history.find { it.payment.id == id } }
        if(action != HistoryAction.AddPayment && item == null) { mutable.value = state.value.copy(error = R.string.history_payment_missing); return }
        val refund = refundId?.let { id -> item?.refunds?.find { it.id == id } }
        if(action in setOf(HistoryAction.EditRefund,HistoryAction.DeleteRefund) && refund == null) {
            mutable.value = state.value.copy(error = R.string.history_refund_missing); return
        }
        item?.let { saved["payment"] = HistorySnapshots.payment(it.payment) }
        refund?.let { saved["refund"] = HistorySnapshots.refund(it) }
        val money = when(action) {
            HistoryAction.AddPayment -> Money(data.subscription.priceMinor,data.subscription.currencyCode)
            HistoryAction.AddRefund -> requireNotNull(item).netExpense
            HistoryAction.EditRefund,HistoryAction.DeleteRefund -> Money(requireNotNull(refund).amountMinor,refund.currencyCode)
            else -> Money(requireNotNull(item).payment.amountMinor,item.payment.currencyCode)
        }
        val date = when(action) {
            HistoryAction.EditPayment,HistoryAction.DeletePayment -> requireNotNull(item).payment.paidOn
            HistoryAction.EditRefund,HistoryAction.DeleteRefund -> requireNotNull(refund).refundedOn
            else -> owner.today()
        }
        val fields = hashMapOf("date" to date.toString(),"amount" to money.format(),"currency" to money.currency.value)
        saved["newId"] = UUID.randomUUID().toString(); saved["recordedAt"] = Instant.now().toString()
        saved["fields"] = fields; saved["action"] = action.name
        mutable.value = state.value.copy(action = action,fields = fields,error = null,errors = emptyMap(),conflicted = false)
    }
    fun change(key: String,value: String) {
        if(state.value.frozen || state.value.phase == HistoryPhase.OperationInProgress || state.value.conflicted) return
        saved["fields"] = HashMap(state.value.fields + (key to value))
        mutable.value = state.value.copy(fields = state.value.fields + (key to value),errors = emptyMap(),error = null)
    }
    fun dismiss() {
        if(state.value.phase == HistoryPhase.OperationInProgress) return
        if(state.value.frozen) { mutable.value = state.value.copy(error = R.string.history_retry_same); return }
        clearDraft()
    }
    private fun clearDraft() {
        listOf("action","fields","payment","refund","newId","recordedAt","sent","conflicted").forEach { saved.remove<Any>(it) }
        mutable.value = state.value.copy(action = null,fields = emptyMap(),errors = emptyMap(),error = null,frozen = false,conflicted = false)
    }
    internal fun validate(fields: Map<String,String>, today: LocalDate): Map<String,Int> {
        val errors = mutableMapOf<String,Int>()
        try { require(LocalDate.parse(fields.getValue("date")) <= today) } catch(_: Exception) { errors["date"] = R.string.history_invalid_date }
        try { CurrencyCode.of(fields.getValue("currency")) } catch(_: Exception) { errors["currency"] = R.string.history_invalid_currency }
        if("currency" !in errors) try { Money.parse(fields.getValue("amount"),CurrencyCode.of(fields.getValue("currency"))) }
        catch(_: Exception) { errors["amount"] = R.string.history_invalid_amount }
        return errors
    }
    fun submit() {
        if(state.value.phase in setOf(HistoryPhase.Loading,HistoryPhase.Error,HistoryPhase.OperationInProgress) || state.value.conflicted) return
        val action = state.value.action ?: return
        val fields = state.value.fields
        val deleting = action in setOf(HistoryAction.DeletePayment,HistoryAction.DeleteRefund)
        val errors = if(deleting || state.value.frozen) emptyMap() else validate(fields,owner.today())
        if(errors.isNotEmpty()) { mutable.value = state.value.copy(errors = errors); return }
        val payment = saved.get<ArrayList<String>>("payment")?.let(HistorySnapshots::payment)
        val refund = saved.get<ArrayList<String>>("refund")?.let(HistorySnapshots::refund)
        val newId = UUID.fromString(saved.get<String>("newId"))
        val recorded = Instant.parse(saved.get<String>("recordedAt"))
        val date = if(deleting) null else LocalDate.parse(fields.getValue("date"))
        val amount = if(deleting) null else Money.parse(fields.getValue("amount"),CurrencyCode.of(fields.getValue("currency")))
        saved["sent"] = true
        mutable.value = state.value.copy(phase = HistoryPhase.OperationInProgress,error = null,errors = emptyMap(),frozen = true)
        operation = owner.viewModelScope.launch {
            try {
                owner.perform { use ->
                    when(action) {
                        HistoryAction.AddPayment -> use.addHistoricalPayment(OperationId(newId),SubscriptionId(subscriptionId),requireNotNull(date),requireNotNull(amount),recorded)
                        HistoryAction.EditPayment -> use.editPayment(requireNotNull(payment),requireNotNull(date),requireNotNull(amount))
                        HistoryAction.DeletePayment -> use.deletePayment(requireNotNull(payment),confirmed = true)
                        HistoryAction.AddRefund -> use.addRefund(RefundEntity(newId,requireNotNull(payment).id,requireNotNull(date),requireNotNull(amount).amountMinor,amount.currency))
                        HistoryAction.EditRefund -> use.editRefund(requireNotNull(refund),requireNotNull(date),requireNotNull(amount))
                        HistoryAction.DeleteRefund -> use.deleteRefund(requireNotNull(refund))
                    }
                }
                clearDraft()
                mutable.value = state.value.copy(phase = phase(state.value.data))
            } catch(e: CancellationException) { throw e }
            catch(_: HistoryConflict) { conflict() }
            catch(_: PaymentConflict) { conflict() }
            catch(_: IllegalArgumentException) { invalid(action) }
            catch(_: ArithmeticException) { invalid(action) }
            catch(_: Exception) { mutable.value = state.value.copy(phase = phase(state.value.data),error = R.string.history_retry_same) }
        }
    }
    private fun conflict() {
        saved["sent"] = false; saved["conflicted"] = true
        mutable.value = state.value.copy(phase = phase(state.value.data),frozen = false,conflicted = true,error = R.string.editor_conflict)
    }
    private fun invalid(action: HistoryAction) {
        saved["sent"] = false
        val message = if(action in setOf(HistoryAction.AddRefund,HistoryAction.EditRefund)) R.string.history_refund_rules else R.string.history_payment_rules
        mutable.value = state.value.copy(phase = phase(state.value.data),frozen = false,error = message,
            errors = mapOf("date" to message,"amount" to message,"currency" to message))
    }
}
