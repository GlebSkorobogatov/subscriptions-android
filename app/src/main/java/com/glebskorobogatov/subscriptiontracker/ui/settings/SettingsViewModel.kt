package com.glebskorobogatov.subscriptiontracker.ui.settings

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val COST_RANKING = "costRankingPeriod"

enum class SettingsPhase { Loading, Content, Saving, Error }
data class SettingsUiState(val phase: SettingsPhase = SettingsPhase.Loading,val data: Settings? = null,
    val error: Int? = null,val pending: Boolean = false, val rankingPending: Boolean = false)

/** Activity owner: one settings reader supplies both the theme and the settings route. */
class SettingsViewModel(private val saved: SavedStateHandle,private val owner: SubscriptionsViewModel): ViewModel() {
    private fun restorePending(): Boolean {
        listOf("timeOpen","hour","minute","hourInput","minuteInput").forEach { saved.remove<Any>(it) }
        val intent = saved.get<HashMap<String,String>>("intent")
        if (intent != null && !runCatching {
            require(intent["kind"] in setOf("theme","payment","animations",COST_RANKING))
            applySetting(Settings(),intent)
        }.isSuccess) saved.remove<Any>("intent")
        return saved.contains("intent")
    }
    private val mutable = MutableStateFlow(SettingsUiState(
        pending = restorePending(),
        rankingPending = saved.get<HashMap<String,String>>("intent")?.get("kind") == COST_RANKING,
    ))

    private fun isCurrent(intent: Map<String,String>): Boolean =
        intent["kind"] != COST_RANKING || intent["dataset"] == owner.activeDatasetId()?.toString().orEmpty()
    val state = mutable.asStateFlow()
    private val attempts = MutableStateFlow(0)
    private var discardPendingAfterRead = false
    private var operation: Job? = null
    private val reader = owner.viewModelScope.launch {
        combine(owner.generation,attempts) { generation,attempt -> generation to attempt }.collectLatest {
            mutable.value = state.value.copy(phase = SettingsPhase.Loading,error = null)
            try {
                check(owner.state.first { it !is SubscriptionsUiState.Loading } .let { it !is SubscriptionsUiState.Error && it !is SubscriptionsUiState.StorageBlocked })
                owner.settings().collect { value ->
                    if(discardPendingAfterRead) { saved.remove<HashMap<String,String>>("intent"); discardPendingAfterRead = false }
                    val intent = saved.get<HashMap<String,String>>("intent")
                    if (intent != null && !isCurrent(intent)) saved.remove<HashMap<String,String>>("intent")
                    val pending = saved.contains("intent")
                    mutable.value = state.value.copy(data = value,phase = if(state.value.phase == SettingsPhase.Saving) SettingsPhase.Saving else SettingsPhase.Content,
                        pending = pending, rankingPending = pending && intent?.get("kind") == COST_RANKING, error = if(pending && state.value.phase != SettingsPhase.Saving) R.string.settings_uncertain else null)
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { mutable.value = state.value.copy(phase = SettingsPhase.Error,error = R.string.settings_load_error) }
        }
    }
    override fun onCleared() { reader.cancel(); operation?.cancel() }
    fun retryRead() {
        if(state.value.phase == SettingsPhase.Saving) return
        discardPendingAfterRead = true
        if(owner.state.value is SubscriptionsUiState.Error) owner.retry()
        attempts.value++
    }
    fun setTheme(value: Theme) = start(hashMapOf("kind" to "theme","value" to value.name))
    fun setCostRankingPeriod(period: CostRankingPeriod) {
        if (state.value.data?.costRankingPeriod == period) return
        start(hashMapOf("kind" to COST_RANKING, "value" to period.name,
            "dataset" to owner.activeDatasetId()?.toString().orEmpty()))
    }
    fun setAnimations(enabled: Boolean) = start(hashMapOf("kind" to "animations","value" to enabled.toString()))
    fun setOffset(day: Int,enabled: Boolean) {
        require(day in setOf(7,3,1,0))
        start(hashMapOf("kind" to "payment","day" to day.toString(),"enabled" to enabled.toString()))
    }
    private fun start(intent: HashMap<String,String>) {
        if(state.value.phase != SettingsPhase.Content || state.value.pending) return
        saved["intent"] = intent; write(intent)
    }
    fun retryWrite() {
        if(state.value.phase != SettingsPhase.Content) return
        val intent = saved.get<HashMap<String,String>>("intent") ?: return
        if (isCurrent(intent)) write(intent) else retryRead()
    }
    private fun write(intent: Map<String,String>) {
        val ranking = intent["kind"] == COST_RANKING
        mutable.value = state.value.copy(phase = SettingsPhase.Saving,error = null,pending = true, rankingPending = ranking)
        operation = owner.viewModelScope.launch {
            try {
                owner.perform { use ->
                    check(isCurrent(intent)) { "Preference belongs to a different dataset" }
                    use.updateSettings { current -> applySetting(current,intent) }
                }
                if (saved.get<HashMap<String,String>>("intent") != intent) return@launch
                saved.remove<HashMap<String,String>>("intent")
                mutable.value = state.value.copy(
                    phase = if (state.value.phase == SettingsPhase.Saving) SettingsPhase.Content else state.value.phase,
                    error = state.value.error, pending = false, rankingPending = false,
                )
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) {
                if (saved.get<HashMap<String,String>>("intent") != intent) return@launch
                if (!isCurrent(intent)) {
                    saved.remove<HashMap<String,String>>("intent")
                    mutable.value = state.value.copy(pending = false, rankingPending = false)
                    return@launch
                }
                if (state.value.phase == SettingsPhase.Saving || state.value.phase == SettingsPhase.Content) {
                    mutable.value = state.value.copy(phase = SettingsPhase.Content,error = R.string.settings_uncertain,pending = true)
                }
            }
        }
    }
}

/** Assign one preference against the repository's latest value; retry never toggles it back. */
internal fun applySetting(current: Settings,intent: Map<String,String>): Settings {
    fun offsets(old: Set<Int>): Set<Int> {
        val day = intent.getValue("day").toInt(); require(day in setOf(7,3,1,0))
        return if(intent.getValue("enabled").toBooleanStrict()) old + day else old - day
    }
    return when(intent.getValue("kind")) {
        "theme" -> current.copy(theme = Theme.valueOf(intent.getValue("value")))
        "payment" -> current.copy(renewalOffsets = offsets(current.renewalOffsets))
        COST_RANKING -> current.copy(costRankingPeriod = CostRankingPeriod.valueOf(intent.getValue("value")))
        "animations" -> current.copy(animationsEnabled = intent.getValue("value").toBooleanStrict())
        else -> error("Unknown preference")
    }
}
