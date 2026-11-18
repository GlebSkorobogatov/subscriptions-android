package com.glebskorobogatov.subscriptiontracker.ui.details

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionDetails as Detail
import com.glebskorobogatov.subscriptiontracker.data.integration.calculationOrNull
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.mapping.SubscriptionSnapshot
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class DetailsPhase { Loading, Content, NotFound, Error, OperationInProgress }
enum class DetailsAction { Disable, EditEnd, Archive, Reactivate, Delete }
data class DetailsUiState(val today: LocalDate = LocalDate.now(), val phase: DetailsPhase = DetailsPhase.Loading, val data: Detail? = null,
    val error: Int? = null, val action: DetailsAction? = null, val fields: Map<String,String> = emptyMap(),
    val frozen: Boolean = false, val conflicted: Boolean = false, val deleted: Boolean = false,
    val archived: Boolean = false, val customFrequency: Boolean? = null)

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
        val fields = legacyFields.filterKeys { it in setOf("date","end","unit","count","amount","anchorDay") }
        saved["fields"] = HashMap(fields)
        val custom = if (action == DetailsAction.Reactivate) saved.get<Boolean>("frequencyCustom") else null
        if (custom == null) saved.remove<Boolean>("frequencyCustom")
        return DetailsUiState(action = action, fields = fields, frozen = saved["sent"] ?: false, customFrequency = custom)
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
                check(owner.state.first { it !is SubscriptionsUiState.Loading }.let { it !is SubscriptionsUiState.Error && it !is SubscriptionsUiState.StorageBlocked })
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
        DetailsAction.Disable -> row.state == SubscriptionState.ACTIVE
        DetailsAction.Archive -> row.state != SubscriptionState.ARCHIVED
        DetailsAction.Reactivate -> row.state != SubscriptionState.ACTIVE
        DetailsAction.EditEnd -> row.state == SubscriptionState.CANCELLED
        DetailsAction.Delete -> true
    }
    fun open(action: DetailsAction) {
        if (state.value.phase != DetailsPhase.Content || state.value.action != null) return
        val row = state.value.data?.subscription ?: return
        if (!allowed(row, action)) return
        val record = row.toDomain()
        val end = row.cancelledAccessEndExclusive ?: calculationOrNull {
            SubscriptionDomain().nextDue(record.schedule,owner.today())
        }
        val fields = hashMapOf("amount" to RubPrice(row.priceMinor).format(),
            "date" to owner.today().toString(), "end" to end?.toString().orEmpty(),
            "unit" to row.intervalUnit.name, "count" to row.intervalCount.toString())
        saved["snapshot"] = SubscriptionSnapshot.snapshot(row)
        owner.activeDatasetId()?.let { saved["generation"] = it.toString() }
        saved["fields"] = fields; saved["action"] = action.name
        saved.remove<Boolean>("frequencyCustom")
        mutable.value = state.value.copy(action = action,fields = fields,error = null,conflicted = false,customFrequency = null)
    }
    /** Presentation only. Custom editing stays on «Другая» until a preset capsule is chosen. */
    fun setFrequencyMode(custom: Boolean) {
        if (state.value.action != DetailsAction.Reactivate || state.value.frozen || state.value.phase == DetailsPhase.OperationInProgress) return
        if (saved.get<Boolean>("frequencyCustom") == custom && state.value.customFrequency == custom) return
        saved["frequencyCustom"] = custom
        mutable.value = state.value.copy(customFrequency = custom)
    }
    /** One observation for a preset or custom pair. The lifecycle command is still built at submit. */
    fun changeFrequency(unit: String, count: String) {
        if (state.value.action != DetailsAction.Reactivate || state.value.frozen || state.value.phase == DetailsPhase.OperationInProgress) return
        if (unit !in setOf("DAY","WEEK","MONTH","YEAR")) return
        val parsed = count.toIntOrNull() ?: return
        if (parsed < 1) return
        val fields = state.value.fields + ("unit" to unit) + ("count" to parsed.toString())
        saved["fields"] = HashMap(fields)
        mutable.value = state.value.copy(fields = fields,error = null)
    }
    fun change(key: String, value: String) {
        if (key !in setOf("date", "end", "unit", "count", "amount", "anchorDay") || state.value.frozen || state.value.phase == DetailsPhase.OperationInProgress) return
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
        listOf("action","fields","snapshot","sent","operation","generation","frequencyCustom").forEach { saved.remove<Any>(it) }
    }
    private fun clearDraft() {
        clearSavedDraft()
        mutable.value = state.value.copy(action = null,fields = emptyMap(),frozen = false,conflicted = false,error = null,customFrequency = null)
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
                DetailsAction.Disable -> LifecycleCommand.Cancel(LocalDate.parse(fields.getValue("end")))
                DetailsAction.EditEnd -> LifecycleCommand.EditEnd(LocalDate.parse(fields.getValue("end")))
                DetailsAction.Archive -> LifecycleCommand.Archive
                DetailsAction.Reactivate -> LifecycleCommand.Reactivate(RubPrice.parse(fields.getValue("amount")),
                    ScheduleEdit(LocalDate.parse(fields.getValue("date")),
                        Frequency(FrequencyUnit.valueOf(fields.getValue("unit")), fields.getValue("count").toInt()),
                        fields["anchorDay"]?.takeIf(String::isNotBlank)?.toInt()))
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
                    deleted = action == DetailsAction.Delete, archived = action == DetailsAction.Archive)
            } catch (e: CancellationException) { throw e }
            catch (_: LifecycleConflict) { conflict() }
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
