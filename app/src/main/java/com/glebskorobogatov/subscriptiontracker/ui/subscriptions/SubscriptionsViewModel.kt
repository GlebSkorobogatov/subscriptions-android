package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

fun interface SubscriptionsSource {
    fun activeDatasetId(): java.util.UUID? = null
    fun settings(): Flow<com.glebskorobogatov.subscriptiontracker.data.settings.Settings> = flow { error("Settings stream unavailable") }
    fun finance(): Flow<com.glebskorobogatov.subscriptiontracker.ui.finance.FinanceSnapshot> = flow { error("Finance stream unavailable") }
    fun archive(): Flow<List<com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity>> = flow { error("Archive stream unavailable") }
    fun today(): java.time.LocalDate = java.time.LocalDate.now()
    suspend fun <T> perform(block: suspend (com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases) -> T): T = error("Session unavailable")
    fun details(id: java.util.UUID): Flow<com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionDetails?> = flow {
        emit(perform { it.details(id).first() })
    }
    fun observe(refreshes: Flow<Unit>): Flow<SubscriptionsUiState>
}

class SubscriptionsViewModel(private val source: SubscriptionsSource) : ViewModel() {
    private val mutable = MutableStateFlow<SubscriptionsUiState>(SubscriptionsUiState.Loading)
    val state: StateFlow<SubscriptionsUiState> = mutable.asStateFlow()
    private val attempts = MutableStateFlow(0L)
    val generation = attempts.asStateFlow()
    private val refreshes = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            attempts.collectLatest {
                mutable.value = SubscriptionsUiState.Loading
                try {
                    source.observe(refreshes.receiveAsFlow()).collect { mutable.value = it }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Opening, reading, calendar and overflow failures are never an empty dataset.
                    mutable.value = SubscriptionsUiState.Error
                }
            }
        }
    }
    suspend fun <T> perform(block: suspend (com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases) -> T): T = source.perform(block)
    fun activeDatasetId() = source.activeDatasetId()
    fun settings() = source.settings()
    fun archive() = source.archive()
    fun finance() = source.finance()
    fun today() = source.today()
    fun details(id: java.util.UUID) = source.details(id)
    fun retry() { mutable.value = SubscriptionsUiState.Loading; attempts.value++ }
    fun refreshToday() { refreshes.trySend(Unit) }
}
