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

/** Fixed projection using the existing engine; no configurable financial selection. */
data class FinanceSnapshot(val forecast: ForecastResult, val rows: List<SubscriptionEntity>) {
    val horizon: Int get() = 12
    val currency: CurrencyCode get() = CurrencyCode.of("RUB")
}
internal fun financeSnapshots(use: SubscriptionUseCases): Flow<FinanceSnapshot> =
    combine(use.selectedChart,use.subscriptions) { forecast,rows -> FinanceSnapshot(forecast,rows) }

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
    val currency: CurrencyCode? = null, val month: YearMonth? = null)

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
                        val selected = saved.get<String>("month")?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
                            ?.takeIf { it in snapshot.forecast.months } ?: snapshot.forecast.months.keys.firstOrNull()
                        saved["month"] = selected?.toString()
                        val currency = snapshot.currency.takeIf { it in snapshot.forecast.availableCurrencies }
                        mutable.value = FinanceUiState(if(currency == null) FinancePhase.Empty else FinancePhase.Content,
                            snapshot,currency,selected)
                    }
                } catch(e: CancellationException) { throw e }
                catch(_: Exception) { mutable.value = FinanceUiState(phase = FinancePhase.Error) }
            }
        }
    }
    fun retry() { source.retry(); attempts.value++ }
    fun selectMonth(month: YearMonth) {
        if(month !in state.value.snapshot?.forecast?.months.orEmpty()) return
        saved["month"] = month.toString(); mutable.value = state.value.copy(month = month)
    }
}
