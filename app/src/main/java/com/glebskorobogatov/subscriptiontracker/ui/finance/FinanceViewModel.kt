package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Presentation name for the one authoritative forecast/metadata projection. */
typealias FinanceSnapshot = com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionForecastSnapshot
internal fun financeSnapshots(use: SubscriptionUseCases): Flow<FinanceSnapshot> = use.forecasts

interface FinanceSource {
    fun observe(): Flow<FinanceSnapshot>
    fun retry() {}
}
@OptIn(ExperimentalCoroutinesApi::class)
private class SessionFinanceSource(private val owner: SubscriptionsViewModel): FinanceSource {
    override fun observe() = owner.generation.flatMapLatest {
        flow {
            check(owner.state.first { it !is SubscriptionsUiState.Loading }.let {
                it !is SubscriptionsUiState.Error && it !is SubscriptionsUiState.StorageBlocked
            })
            emitAll(owner.finance())
        }
    }
    override fun retry() { if(owner.state.value is SubscriptionsUiState.Error) owner.retry() }
}
enum class FinancePhase { Loading, Empty, Content, Error }
data class FinanceUiState(val phase: FinancePhase = FinancePhase.Loading, val snapshot: FinanceSnapshot? = null,
    val month: YearMonth? = null)

class FinanceViewModel(private val saved: SavedStateHandle,private val source: FinanceSource): ViewModel() {
    constructor(saved: SavedStateHandle,owner: SubscriptionsViewModel): this(saved,SessionFinanceSource(owner))
    private val mutable = MutableStateFlow(FinanceUiState())
    val state = mutable.asStateFlow()
    private val attempts = MutableStateFlow(0)
    init {
        // Retired temporary selections cannot drive writes or override the fixed projection.
        listOf("horizon","currency","chart_months","chart_currency").forEach { saved.remove<Any>(it) }
        viewModelScope.launch {
            attempts.collectLatest {
                mutable.value = FinanceUiState()
                try {
                    source.observe().collect { snapshot ->
                        val first = YearMonth.from(snapshot.today)
                        val selected = saved.get<String>("month")?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
                            ?.takeIf { java.time.temporal.ChronoUnit.MONTHS.between(first,it) in 0L..11L } ?: first
                        saved["month"] = selected.toString()
                        val forecast = snapshot.forecast
                        mutable.value = FinanceUiState(when {
                            forecast == null -> FinancePhase.Error
                            forecast.charges.isEmpty() -> FinancePhase.Empty
                            else -> FinancePhase.Content
                        },snapshot,selected)
                    }
                } catch(e: CancellationException) { throw e }
                catch(_: Exception) { mutable.value = FinanceUiState(phase = FinancePhase.Error) }
            }
        }
    }
    fun retry() { source.retry(); attempts.value++ }
    fun selectMonth(month: YearMonth) {
        val snapshot = state.value.snapshot ?: return
        if (java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(snapshot.today),month) !in 0L..11L) return
        saved["month"] = month.toString(); mutable.value = state.value.copy(month = month)
    }
}
