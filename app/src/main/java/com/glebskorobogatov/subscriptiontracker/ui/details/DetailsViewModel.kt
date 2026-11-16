package com.glebskorobogatov.subscriptiontracker.ui.details

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionDetails as Detail
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.mapping.SubscriptionSnapshot
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class DetailsPhase { Loading, Content, NotFound, Error, OperationInProgress }
enum class DetailsAction { ResolveDate, Disable, Archive, Reactivate, Delete }
data class DetailsUiState(val today: LocalDate = LocalDate.now(), val phase: DetailsPhase = DetailsPhase.Loading, val data: Detail? = null,
    val error: Int? = null, val action: DetailsAction? = null, val fields: Map<String,String> = emptyMap(),
    val frozen: Boolean = false, val conflicted: Boolean = false, val deleted: Boolean = false)

class DetailsViewModel(private val saved: SavedStateHandle, private val owner: SubscriptionsViewModel,
    private val id: UUID) : ViewModel() {
    private fun restored(): DetailsUiState {
        val action = DetailsAction.entries.firstOrNull { it.name == saved.get<String>("action") }
        val valid = action != null && runCatching { expected().id == id }.getOrDefault(false)
        saved.remove<String>("operation") // Former payment receipt identity has no consumer.
        if (!valid) {
            clearSavedDraft()
            return DetailsUiState()
        }
        val legacyFields = saved.get<HashMap<String,String>>("fields").orEmpty()
        if (action == DetailsAction.Reactivate && legacyFields["currency"]?.let { it != expected().currencyCode.value } == true) {
            clearSavedDraft(); return DetailsUiState()
        }
        val fields = legacyFields.filterKeys {
            it in setOf("date","end","unit","count","mode","amount","reanchor")
        }
        saved["fields"] = HashMap(fields)
        return DetailsUiState(action = action, fields = fields, frozen = saved["sent"] ?: false)
    }
    private val mutable = MutableStateFlow(restored())
    val state = mutable.asStateFlow()
    private var reader: Job? = null
    private var operation: Job? = null
    init { refresh(discard = false) }
    override fun onCleared() { reader?.cancel(); operation?.cancel() }
    fun refresh() = refresh(discard = true)
    private fun refresh(discard: Boolean) {
        if (state.value.phase == DetailsPhase.OperationInProgress) return
        // Explicit refresh can abandon an uncertain non-payment draft and read authoritative state.
        if (discard && (state.value.conflicted || state.value.frozen)) clearDraft()
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
    private fun allowed(row: SubscriptionEntity, action: DetailsAction): Boolean = when (action) {
        DetailsAction.ResolveDate -> row.lifecycleState == LifecycleState.PAYMENT_ISSUE
        DetailsAction.Disable -> row.lifecycleState in setOf(LifecycleState.ACTIVE, LifecycleState.PAYMENT_ISSUE)
        DetailsAction.Archive -> row.lifecycleState != LifecycleState.ARCHIVED
        DetailsAction.Reactivate -> row.lifecycleState == LifecycleState.ARCHIVED
        DetailsAction.Delete -> true
    }
    fun open(action: DetailsAction) {
        if (state.value.phase != DetailsPhase.Content || state.value.action != null) return
        val row = state.value.data?.subscription ?: return
        if (!allowed(row, action)) return
        val fields = hashMapOf("reanchor" to "false", "amount" to Money(row.priceMinor,row.currencyCode).format(),
            "date" to owner.today().toString(), "end" to (row.paidAccessEndDate?.toString() ?: ""),
            "unit" to row.intervalUnit.name, "count" to row.intervalCount.toString(), "mode" to row.renewalMode.name)
        saved["snapshot"] = SubscriptionSnapshot.snapshot(row)
        owner.activeDatasetId()?.let { saved["generation"] = it.toString() }
        saved["fields"] = fields; saved["action"] = action.name
        mutable.value = state.value.copy(action = action,fields = fields,error = null,conflicted = false)
    }
    fun change(key: String, value: String) {
        if (key == "currency" || state.value.frozen || state.value.phase == DetailsPhase.OperationInProgress) return
        val fields = state.value.fields + (key to value)
        saved["fields"] = HashMap(fields)
        mutable.value = state.value.copy(fields = fields,error = null)
    }
    private fun expected() = SubscriptionSnapshot.row(requireNotNull(saved.get<ArrayList<String>>("snapshot")))
    fun dismiss() {
        if (state.value.phase == DetailsPhase.OperationInProgress) return
        if (state.value.frozen) { mutable.value = state.value.copy(error = R.string.details_retry_same); return }
        clearDraft()
    }
    private fun clearSavedDraft() {
        listOf("action","fields","snapshot","sent","operation","generation").forEach { saved.remove<Any>(it) }
    }
    private fun clearDraft() {
        clearSavedDraft()
        mutable.value = state.value.copy(action = null,fields = emptyMap(),frozen = false,conflicted = false,error = null)
    }
    fun submit() {
        if (state.value.phase == DetailsPhase.OperationInProgress || state.value.action == null || state.value.conflicted) return
        val action = state.value.action!!
        val fields = state.value.fields
        val snapshot: SubscriptionEntity
        val command: LifecycleCommand?
        val generation: String?
        try {
            snapshot = expected()
            require(snapshot.id == id && allowed(snapshot, action))
            generation = saved.get<String>("generation")
            if (generation != null && generation != owner.activeDatasetId()?.toString()) { conflict(); return }
            command = when (action) {
                DetailsAction.ResolveDate -> LifecycleCommand.ResolveWithDate(LocalDate.parse(fields.getValue("date")), fields["reanchor"] == "true")
                DetailsAction.Disable -> LifecycleCommand.DisableRenewal(fields["end"]?.takeIf(String::isNotBlank)?.let(LocalDate::parse))
                DetailsAction.Archive -> LifecycleCommand.Archive
                DetailsAction.Reactivate -> LifecycleCommand.Reactivate(LocalDate.parse(fields.getValue("date")),
                    Frequency(FrequencyUnit.valueOf(fields.getValue("unit")),fields.getValue("count").toInt()),
                    RenewalMode.valueOf(fields.getValue("mode")), Money.parse(fields.getValue("amount"),snapshot.currencyCode))
                DetailsAction.Delete -> null
            }
        } catch (_: Exception) { mutable.value = state.value.copy(error = R.string.details_invalid); return }
        saved["sent"] = true
        mutable.value = state.value.copy(phase = DetailsPhase.OperationInProgress,error = null,frozen = true)
        operation = owner.viewModelScope.launch {
            try {
                owner.perform { use ->
                    if (generation != null && generation != owner.activeDatasetId()?.toString()) throw LifecycleConflict()
                    if (action == DetailsAction.Delete) use.deleteSubscription(snapshot, confirmed = true)
                    else use.changeLifecycle(snapshot,requireNotNull(command),Instant.now())
                }
                clearDraft()
                mutable.value = state.value.copy(phase = if (state.value.data == null) DetailsPhase.NotFound else DetailsPhase.Content,
                    deleted = action == DetailsAction.Delete)
            } catch (e: CancellationException) { throw e }
            catch (_: LifecycleConflict) { conflict() }
            catch (_: com.glebskorobogatov.subscriptiontracker.domain.calendar.CycleConflictException) { rejected(R.string.details_cycle_conflict) }
            catch (_: IllegalArgumentException) { rejected(R.string.details_invalid) }
            catch (_: Exception) {
                mutable.value = state.value.copy(phase = if (state.value.data == null) DetailsPhase.NotFound else DetailsPhase.Content,
                    error = R.string.details_retry_same)
            }
        }
    }
    private fun rejected(message: Int) {
        saved["sent"] = false
        mutable.value = state.value.copy(phase = DetailsPhase.Content,frozen = false,error = message)
    }
    private fun conflict() {
        saved["sent"] = false
        mutable.value = state.value.copy(phase = if (state.value.data == null) DetailsPhase.NotFound else DetailsPhase.Content,
            frozen = false,conflicted = true,error = R.string.editor_conflict)
    }
}
