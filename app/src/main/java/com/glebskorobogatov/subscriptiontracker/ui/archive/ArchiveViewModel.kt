package com.glebskorobogatov.subscriptiontracker.ui.archive

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases
import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Join existing classification with storage metadata; suppress mismatched Room emissions. */
internal fun archiveRows(use: SubscriptionUseCases): Flow<List<SubscriptionEntity>> =
    combine(use.archive,use.subscriptions) { archived,rows ->
        val stored = rows.filter { it.id in archived.map { s -> s.id.value }.toSet() }
        if(stored.map { it.toDomain() }.associateBy { it.id } == archived.associateBy { it.id }) stored else null
    }.filterNotNull().distinctUntilChanged()
internal fun sortedArchive(rows: List<SubscriptionEntity>) = rows.sortedWith(
    compareBy<SubscriptionEntity, java.time.LocalDate?>(nullsLast(reverseOrder())) { it.archivedAt }.thenBy { it.id.toString() })
enum class ArchivePhase { Loading, Empty, Content, Error }
data class ArchiveUiState(val phase: ArchivePhase = ArchivePhase.Loading,val rows: List<SubscriptionEntity> = emptyList())

class ArchiveViewModel(private val owner: SubscriptionsViewModel): ViewModel() {
    private val mutable = MutableStateFlow(ArchiveUiState())
    val state = mutable.asStateFlow()
    private val attempts = MutableStateFlow(0)
    private val reader = owner.viewModelScope.launch {
        combine(owner.generation,attempts) { generation,attempt -> generation to attempt }.collectLatest {
            mutable.value = state.value.copy(phase = ArchivePhase.Loading)
            try {
                check(owner.state.first { it !is SubscriptionsUiState.Loading } !is SubscriptionsUiState.Error)
                owner.archive().collect { rows -> mutable.value = ArchiveUiState(
                    if(rows.isEmpty()) ArchivePhase.Empty else ArchivePhase.Content,sortedArchive(rows)) }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { mutable.value = state.value.copy(phase = ArchivePhase.Error) }
        }
    }
    fun retry() { if(owner.state.value is SubscriptionsUiState.Error) owner.retry(); attempts.value++ }
    override fun onCleared() { reader.cancel() }
}
