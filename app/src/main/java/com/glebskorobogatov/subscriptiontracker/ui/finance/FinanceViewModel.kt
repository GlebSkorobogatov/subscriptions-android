package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastResult
import com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class FinanceSnapshot(val forecast: ForecastResult, val rows: List<SubscriptionEntity>,
    val horizon: Int, val currency: CurrencyCode?)

/** Existing domain results and current Room names only; no history, sums or calendar algorithm here. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun financeSnapshots(use: SubscriptionUseCases): Flow<FinanceSnapshot> =
    use.settings.map { it.chartHorizonMonths to it.selectedChartCurrency }.distinctUntilChanged().flatMapLatest { (months,currency) ->
        combine(use.forecastMonths(months,currency),use.subscriptions) { forecast,rows ->
            FinanceSnapshot(forecast,rows,months,currency)
        }
    }

interface FinanceSource {
    fun observe(): Flow<FinanceSnapshot>
    suspend fun select(horizon: Int, currency: CurrencyCode?)
    /** Compare before repairing an unavailable saved currency; never overwrite a newer user selection. */
    suspend fun fallback(snapshot: FinanceSnapshot, currency: CurrencyCode)
    fun retry() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
private class SessionFinanceSource(private val owner: SubscriptionsViewModel): FinanceSource {
    override fun observe() = owner.generation.flatMapLatest {
        flow {
            check(owner.state.first { it !is SubscriptionsUiState.Loading } !is SubscriptionsUiState.Error)
            emitAll(owner.finance())
        }
    }
    override suspend fun select(horizon: Int,currency: CurrencyCode?) {
        owner.perform { it.updateSettings { old -> old.copy(chartHorizonMonths = horizon,selectedChartCurrency = currency) } }
    }
    override suspend fun fallback(snapshot: FinanceSnapshot,currency: CurrencyCode) {
        owner.perform { it.updateSettings { old ->
            if(old.chartHorizonMonths == snapshot.horizon && old.selectedChartCurrency == snapshot.currency)
                old.copy(selectedChartCurrency = currency) else old
        } }
    }
    override fun retry() { if(owner.state.value is SubscriptionsUiState.Error) owner.retry() }
}

enum class FinancePhase { Loading, Empty, Content, Error }
data class FinanceUiState(val phase: FinancePhase = FinancePhase.Loading, val snapshot: FinanceSnapshot? = null,
    val currency: CurrencyCode? = null, val month: YearMonth? = null, val saving: Boolean = false,
    val saveError: Boolean = false)

class FinanceViewModel(private val saved: SavedStateHandle,private val source: FinanceSource): ViewModel() {
    constructor(saved: SavedStateHandle,owner: SubscriptionsViewModel): this(saved,SessionFinanceSource(owner))
    private val mutable = MutableStateFlow(FinanceUiState())
    val state = mutable.asStateFlow()
    private val attempts = MutableStateFlow(0)
    private var write: Job? = null
    init { viewModelScope.launch {
        attempts.collectLatest {
            mutable.value = FinanceUiState()
            try {
                source.observe().collect { snapshot ->
                    val available = snapshot.forecast.availableCurrencies
                    val currency = snapshot.currency?.takeIf { it in available } ?: available.firstOrNull()
                    if(currency != null && snapshot.currency != currency) {
                        // Re-read through the same filtered UseCase after persisting the deterministic fallback.
                        mutable.value = FinanceUiState(saving = true)
                        source.fallback(snapshot,currency)
                    } else {
                        val selected = saved.get<String>("month")?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
                            ?.takeIf { it in snapshot.forecast.months } ?: snapshot.forecast.months.keys.firstOrNull()
                        saved["month"] = selected?.toString()
                        mutable.value = FinanceUiState(if(currency == null) FinancePhase.Empty else FinancePhase.Content,
                            snapshot,currency,selected,saving = write?.isActive == true)
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { mutable.value = FinanceUiState(phase = FinancePhase.Error) }
        }
    } }
    fun retry() { source.retry(); attempts.value++ }
    fun selectMonth(month: YearMonth) {
        if(month !in state.value.snapshot?.forecast?.months.orEmpty()) return
        saved["month"] = month.toString(); mutable.value = state.value.copy(month = month)
    }
    fun selectHorizon(months: Int) {
        require(months in setOf(3,6,12)); choose(months,state.value.currency)
    }
    fun selectCurrency(currency: CurrencyCode) {
        if(currency !in state.value.snapshot?.forecast?.availableCurrencies.orEmpty()) return
        choose(state.value.snapshot?.horizon ?: return,currency)
    }
    private fun choose(months: Int,currency: CurrencyCode?) {
        if(state.value.saving || state.value.snapshot == null) return
        mutable.value = state.value.copy(saving = true,saveError = false)
        write = viewModelScope.launch {
            try { source.select(months,currency) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { mutable.value = state.value.copy(saveError = true) }
            finally { mutable.value = state.value.copy(saving = false) }
        }
    }
}
