package com.glebskorobogatov.subscriptiontracker.ui.settings

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.LocalTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class SettingsPhase { Loading, Content, Saving, Error }
data class SettingsUiState(val phase: SettingsPhase = SettingsPhase.Loading,val data: Settings? = null,
    val error: Int? = null,val pending: Boolean = false,val timeOpen: Boolean = false,val hour: Int = 9,val minute: Int = 0,
    val timeError: Int? = null,val hourInput: String = "09",val minuteInput: String = "00")

/** Activity owner: one settings reader supplies both the theme and the settings route. */
class SettingsViewModel(private val saved: SavedStateHandle,private val owner: SubscriptionsViewModel): ViewModel() {
    private val mutable = MutableStateFlow(SettingsUiState(pending = saved.contains("intent"),timeOpen = saved["timeOpen"] ?: false,
        hour = saved["hour"] ?: 9,minute = saved["minute"] ?: 0,
        hourInput = saved["hourInput"] ?: (saved.get<Int>("hour") ?: 9).toString().padStart(2,'0'),
        minuteInput = saved["minuteInput"] ?: (saved.get<Int>("minute") ?: 0).toString().padStart(2,'0')))
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
    fun setOffset(payment: Boolean,day: Int,enabled: Boolean) {
        require(day in setOf(7,3,1,0))
        start(hashMapOf("kind" to if(payment) "payment" else "cancellation","day" to day.toString(),"enabled" to enabled.toString()))
    }
    fun openTime() {
        val data = state.value.data ?: return
        if(state.value.phase != SettingsPhase.Content || state.value.pending) return
        saved["timeOpen"] = true; timeDraft(data.reminderLocalTime.hour,data.reminderLocalTime.minute)
        mutable.value = state.value.copy(timeOpen = true,timeError = null)
    }
    fun timeDraft(hour: Int,minute: Int) {
        saved["hour"] = hour; saved["minute"] = minute
        saved["hourInput"] = hour.toString().padStart(2,'0'); saved["minuteInput"] = minute.toString().padStart(2,'0')
        mutable.value = state.value.copy(hour = hour,minute = minute,timeError = null,
            hourInput = hour.toString().padStart(2,'0'),minuteInput = minute.toString().padStart(2,'0'))
    }
    fun changeTimeInput(hour: Boolean,value: String) {
        saved[if(hour) "hourInput" else "minuteInput"] = value
        mutable.value = if(hour) state.value.copy(hourInput = value,timeError = null) else state.value.copy(minuteInput = value,timeError = null)
    }
    fun confirmTimeInput() {
        val hour = state.value.hourInput; val minute = state.value.minuteInput
        if(!hour.matches(Regex("[0-9]{1,2}")) || !minute.matches(Regex("[0-9]{1,2}"))) {
            mutable.value = state.value.copy(timeError = R.string.settings_invalid_time); return
        }
        confirmTime(hour.toInt(),minute.toInt())
    }
    fun dismissTime() { saved["timeOpen"] = false; mutable.value = state.value.copy(timeOpen = false,timeError = null) }
    fun confirmTime(hour: Int,minute: Int) {
        val time = try { LocalTime.of(hour,minute) } catch(_: Exception) {
            mutable.value = state.value.copy(timeError = R.string.settings_invalid_time); return
        }
        dismissTime(); start(hashMapOf("kind" to "time","value" to time.toString()))
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
        "time" -> current.copy(reminderLocalTime = LocalTime.parse(intent.getValue("value")))
        "payment" -> current.copy(paymentOffsetsDefault = offsets(current.paymentOffsetsDefault))
        "cancellation" -> current.copy(cancellationOffsetsDefault = offsets(current.cancellationOffsetsDefault))
        else -> error("Unknown preference")
    }
}
