package com.glebskorobogatov.subscriptiontracker.ui.settings

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class SettingsPhase { Loading, Content, Saving, Error }
data class SettingsUiState(val phase: SettingsPhase = SettingsPhase.Loading,val data: Settings? = null,
    val error: Int? = null,val pending: Boolean = false)

/** Activity owner: one settings reader supplies both the theme and the settings route. */
class SettingsViewModel(private val saved: SavedStateHandle,private val owner: SubscriptionsViewModel): ViewModel() {
    private fun restorePending(): Boolean {
        listOf("timeOpen","hour","minute","hourInput","minuteInput").forEach { saved.remove<Any>(it) }
        val intent = saved.get<HashMap<String,String>>("intent")
        if (intent != null && !runCatching {
            require(intent["kind"] in setOf("theme","payment"))
            applySetting(Settings(),intent)
        }.isSuccess) saved.remove<Any>("intent")
        return saved.contains("intent")
    }
    private val mutable = MutableStateFlow(SettingsUiState(pending = restorePending()))
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
                    val pending = saved.contains("intent")
                    mutable.value = state.value.copy(data = value,phase = if(state.value.phase == SettingsPhase.Saving) SettingsPhase.Saving else SettingsPhase.Content,
                        pending = pending,error = if(pending && state.value.phase != SettingsPhase.Saving) R.string.settings_uncertain else null)
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
        saved.get<HashMap<String,String>>("intent")?.let(::write)
    }
    private fun write(intent: Map<String,String>) {
        mutable.value = state.value.copy(phase = SettingsPhase.Saving,error = null,pending = true)
        operation = owner.viewModelScope.launch {
            try {
                owner.perform { it.updateSettings { current -> applySetting(current,intent) } }
                saved.remove<HashMap<String,String>>("intent")
                mutable.value = state.value.copy(phase = SettingsPhase.Content,error = null,pending = false)
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { mutable.value = state.value.copy(phase = SettingsPhase.Content,error = R.string.settings_uncertain,pending = true) }
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
        "payment" -> current.copy(paymentOffsetsDefault = offsets(current.paymentOffsetsDefault))
        else -> error("Unknown preference")
    }
}
