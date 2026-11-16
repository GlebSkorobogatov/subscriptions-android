package com.glebskorobogatov.subscriptiontracker.ui.details

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionDetails as Detail
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.payment.toDomain
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.payment.PaymentConflict
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class DetailsPhase { Loading, Content, NotFound, Error, OperationInProgress }
enum class DetailsAction { Payment, MarkIssue, ResolveDate, Disable, Archive, Reactivate, Delete }
data class DetailsUiState(val today: LocalDate = LocalDate.now(), val phase: DetailsPhase = DetailsPhase.Loading, val data: Detail? = null,
    val error: Int? = null, val action: DetailsAction? = null, val fields: Map<String,String> = emptyMap(),
    val frozen: Boolean = false, val conflicted: Boolean = false, val deleted: Boolean = false)

class DetailsViewModel(private val saved: SavedStateHandle, private val owner: SubscriptionsViewModel,
    private val id: UUID) : ViewModel() {
    private val mutable = MutableStateFlow(DetailsUiState(action = saved.get<String>("action")?.let(DetailsAction::valueOf),
        fields = saved.get<HashMap<String,String>>("fields") ?: emptyMap(), frozen = saved["sent"] ?: false))
    val state = mutable.asStateFlow()
    private var reader: Job? = null
    private var operation: Job? = null
    init { refresh() }
    override fun onCleared() { reader?.cancel(); operation?.cancel() }
    fun refresh() {
        if (state.value.phase == DetailsPhase.OperationInProgress) return
        if (state.value.conflicted && !state.value.frozen) clearDraft()
        if (owner.state.value is SubscriptionsUiState.Error) owner.retry()
        reader?.cancel()
        reader = owner.viewModelScope.launch {
            mutable.value = state.value.copy(phase = DetailsPhase.Loading, error = null)
            try {
                owner.state.first { it !is SubscriptionsUiState.Loading }
                owner.details(id).collect { detail ->
                    mutable.value = state.value.copy(data = detail,today = owner.today(),
                        phase = if (state.value.phase == DetailsPhase.OperationInProgress) DetailsPhase.OperationInProgress
                            else if (detail == null) DetailsPhase.NotFound else DetailsPhase.Content)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = state.value.copy(phase = DetailsPhase.Error, error = R.string.details_load_error) }
        }
    }
    fun open(action: DetailsAction) {
        if (state.value.phase != DetailsPhase.Content || state.value.action != null) return
        val row = state.value.data?.subscription ?: return
        val today = LocalDate.now()
        val fields = hashMapOf("reanchor" to "false","needsNext" to (row.renewalMode == RenewalMode.MANUAL || row.lifecycleState == LifecycleState.PAYMENT_ISSUE).toString(),"paid" to today.toString(),"amount" to Money(row.priceMinor,row.currencyCode).format(),
            "currency" to row.currencyCode.value,"date" to today.toString(),"end" to (row.paidAccessEndDate?.toString() ?: ""),
            "unit" to row.intervalUnit.name,"count" to row.intervalCount.toString(),"mode" to row.renewalMode.name,"priceChoice" to "")
        fields["next"] = ""
        saved["snapshot"] = PaymentAttempt.snapshot(row)
        saved["operation"] = OperationId.random().toString()
        saved["fields"] = fields; saved["action"] = action.name
        mutable.value = state.value.copy(action = action,fields = fields,error = null,conflicted = false)
        if (action == DetailsAction.Payment && fields["needsNext"] == "true") suggest()
    }
    fun change(key: String, value: String) {
        if (state.value.frozen || state.value.phase == DetailsPhase.OperationInProgress) return
        val fields = state.value.fields + (key to value)
        saved["fields"] = HashMap(fields)
        mutable.value = state.value.copy(fields = fields,error = null)
    }
    fun suggest() {
        if (state.value.frozen) return
        viewModelScope.launch {
            try {
                val row = expected()
                val paid = state.value.fields.getValue("paid")
                val next = owner.perform { it.suggestNextDate(row,LocalDate.parse(paid)) }
                if (state.value.action == DetailsAction.Payment && state.value.fields["paid"] == paid && !state.value.frozen) change("next",next.toString())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = state.value.copy(error = R.string.details_invalid) }
        }
    }
    private fun expected() = PaymentAttempt.row(requireNotNull(saved.get<ArrayList<String>>("snapshot")))
    fun dismiss() {
        if (state.value.phase == DetailsPhase.OperationInProgress) return
        if (state.value.frozen) { mutable.value = state.value.copy(error = R.string.details_retry_same); return }
        clearDraft()
    }
    private fun clearDraft() {
        listOf("action","fields","snapshot","sent","operation").forEach { saved.remove<Any>(it) }
        mutable.value = state.value.copy(action = null, fields = emptyMap(),frozen = false,conflicted = false,error = null)
    }
    fun submit() {
        if (state.value.phase == DetailsPhase.OperationInProgress || state.value.action == null || state.value.conflicted) return
        val action = state.value.action!!
        val fields = state.value.fields
        val snapshot = expected()
        // Validate and freeze the payment before launching any IO. A retry uses these exact scalars.
        val command = try {
            if (action == DetailsAction.Payment) PaymentAttempt.command(snapshot,fields,requireNotNull(saved["operation"]),if (state.value.frozen) maxOf(LocalDate.now(),LocalDate.parse(fields.getValue("paid"))) else LocalDate.now()) else null
        } catch (_: Exception) { mutable.value = state.value.copy(error = R.string.details_invalid); return }
        if (command != null) saved["sent"] = true
        mutable.value = state.value.copy(phase = DetailsPhase.OperationInProgress,error = null,frozen = command != null)
        operation = owner.viewModelScope.launch {
            try {
                owner.perform { use ->
                    when (action) {
                        DetailsAction.Payment -> {
                            if (snapshot.lifecycleState == LifecycleState.PAYMENT_ISSUE) use.resolvePaymentIssue(snapshot,requireNotNull(command),Instant.now())
                            else use.confirmPayment(snapshot,requireNotNull(command),Instant.now())
                        }
                        DetailsAction.MarkIssue -> use.changeLifecycle(snapshot,LifecycleCommand.MarkPaymentIssue,Instant.now())
                        DetailsAction.ResolveDate -> use.changeLifecycle(snapshot,LifecycleCommand.ResolveWithDate(LocalDate.parse(fields.getValue("date")),fields["reanchor"] == "true"),Instant.now())
                        DetailsAction.Disable -> use.changeLifecycle(snapshot,LifecycleCommand.DisableRenewal(fields["end"]?.takeIf(String::isNotBlank)?.let(LocalDate::parse)),Instant.now())
                        DetailsAction.Archive -> use.changeLifecycle(snapshot,LifecycleCommand.Archive,Instant.now())
                        DetailsAction.Reactivate -> use.changeLifecycle(snapshot,LifecycleCommand.Reactivate(LocalDate.parse(fields.getValue("date")),
                            Frequency(FrequencyUnit.valueOf(fields.getValue("unit")),fields.getValue("count").toInt()),RenewalMode.valueOf(fields.getValue("mode")),
                            Money.parse(fields.getValue("amount"),CurrencyCode.of(fields.getValue("currency")))),Instant.now())
                        DetailsAction.Delete -> use.deleteSubscription(snapshot,confirmed = true)
                    }
                }
                clearDraft()
                mutable.value = state.value.copy(phase = if (state.value.data == null) DetailsPhase.NotFound else DetailsPhase.Content,
                    deleted = action == DetailsAction.Delete)
            } catch (e: CancellationException) { throw e }
            catch (_: LifecycleConflict) { conflict() }
            catch (_: PaymentConflict) { conflict() }
            catch (_: com.glebskorobogatov.subscriptiontracker.domain.calendar.CycleConflictException) {
                saved["sent"] = false
                mutable.value = state.value.copy(phase = DetailsPhase.Content,frozen = false,error = R.string.details_cycle_conflict)
            }
            catch (_: IllegalArgumentException) {
                // Domain validation rejected the transaction; no ambiguous receipt was committed.
                saved["sent"] = false
                mutable.value = state.value.copy(phase = DetailsPhase.Content,frozen = false,error = R.string.details_invalid)
            }
            catch (_: Exception) {
                mutable.value = state.value.copy(phase = if (state.value.data == null) DetailsPhase.NotFound else DetailsPhase.Content,
                    error = if (command != null) R.string.details_retry_same else R.string.details_invalid)
            }
        }
    }
    private fun conflict() {
        // The repository definitively rejected the stale command. Never retry it automatically.
        saved["sent"] = false
        mutable.value = state.value.copy(phase = if (state.value.data == null) DetailsPhase.NotFound else DetailsPhase.Content,frozen = false,conflicted = true,error = R.string.editor_conflict)
    }
}
