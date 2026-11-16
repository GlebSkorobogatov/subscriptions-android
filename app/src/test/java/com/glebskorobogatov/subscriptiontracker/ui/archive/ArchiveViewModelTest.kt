package com.glebskorobogatov.subscriptiontracker.ui.archive

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.ui.details.detailsRow
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val store = ViewModelStore()
    @Before fun start() { Dispatchers.setMain(dispatcher) }
    @After fun stop() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private fun model(values: Flow<List<com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity>>): ArchiveViewModel {
        val owner = SubscriptionsViewModel(object: SubscriptionsSource {
            override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
            override fun archive() = values
        }); store.put("owner",owner)
        return ArchiveViewModel(owner).also { store.put("archive",it) }
    }
    @Test fun firstEmissionSeparatesLoadingFromEmpty() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val vm = model(flow { gate.await(); emit(emptyList()) })
        runCurrent(); assertEquals(ArchivePhase.Loading,vm.state.value.phase)
        gate.complete(Unit); runCurrent(); assertEquals(ArchivePhase.Empty,vm.state.value.phase)
    }
    @Test fun errorIsNotEmptyAndExplicitRetryRestoresRows() = runTest(dispatcher) {
        var bad = true; val row = detailsRow()
        val vm = model(flow { if(bad) error("Room"); emit(listOf(row)) }); runCurrent()
        assertEquals(ArchivePhase.Error,vm.state.value.phase)
        bad = false; vm.retry(); runCurrent(); assertEquals(listOf(row),vm.state.value.rows)
        assertEquals(ArchivePhase.Content,vm.state.value.phase)
    }
    @Test fun nameAndUuidSortKeepsDuplicateNamesIndependentWithoutArchiveTimestamp() {
        val a = detailsRow().copy(id = UUID(0,1),name = "Сервис",accountLabel = "Первый")
        val b = a.copy(id = UUID(0,2),accountLabel = "Второй")
        val first = a.copy(id = UUID(0,3),name = "Архив")
        assertEquals(listOf(first,a,b),sortedArchive(listOf(b,first,a)))
    }
    @Test fun sessionRetryRestartsFailedArchiveReader() = runTest(dispatcher) {
        var bad = true
        val owner = SubscriptionsViewModel(object: SubscriptionsSource {
            override fun observe(refreshes: Flow<Unit>) = flow { if(bad) error("open"); emit(SubscriptionsUiState.Empty()) }
            override fun archive() = flowOf(emptyList<com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity>())
        }); store.put("owner",owner); val vm = ArchiveViewModel(owner); store.put("archive",vm)
        runCurrent(); assertEquals(ArchivePhase.Error,vm.state.value.phase)
        bad = false; owner.retry(); runCurrent(); assertEquals(ArchivePhase.Empty,vm.state.value.phase)
    }
}
