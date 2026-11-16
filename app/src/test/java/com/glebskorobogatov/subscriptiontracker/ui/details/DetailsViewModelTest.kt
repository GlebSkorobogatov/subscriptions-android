package com.glebskorobogatov.subscriptiontracker.ui.details

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import java.util.UUID
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var number = 0
    @Before fun start() { Dispatchers.setMain(dispatcher) }
    @After fun close() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private val detail = SubscriptionDetails(detailsRow(),LifecycleView(VisibleStatus.ACTIVE,null,null),emptyList(),emptyList())
    private fun model(handle: SavedStateHandle = SavedStateHandle(), values: Flow<SubscriptionDetails?> = flowOf(detail),
        operation: suspend () -> Unit = { error("Unknown outcome") }): DetailsViewModel {
        val owner = SubscriptionsViewModel(object : SubscriptionsSource {
            override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
            override fun details(id: UUID) = values
            override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T { operation(); error("Not executed") }
        })
        store.put("owner${number++}",owner)
        return DetailsViewModel(handle,owner,detailsRow().id).also { store.put("details${number++}",it) }
    }
    @Test fun loadingWaitsForRoomThenMissingIsNotFound() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = model(values = flow { gate.await(); emit(null) })
        runCurrent(); assertEquals(DetailsPhase.Loading,vm.state.value.phase)
        gate.complete(Unit); runCurrent(); assertEquals(DetailsPhase.NotFound,vm.state.value.phase)
    }
    @Test fun readFailureIsErrorAndNotEmpty() = runTest(dispatcher) {
        val vm = model(values = flow { throw IllegalStateException("Room") })
        runCurrent(); assertEquals(DetailsPhase.Error,vm.state.value.phase)
    }
    @Test fun repeatedSubmitWhileBusyStartsOneOperation() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); var count = 0
        val vm = model(operation = { count++; gate.await() }); runCurrent()
        vm.open(DetailsAction.Payment); vm.submit(); vm.submit(); runCurrent()
        assertEquals(DetailsPhase.OperationInProgress,vm.state.value.phase); assertEquals(1,count)
        gate.complete(Unit); runCurrent(); assertTrue(vm.state.value.frozen)
    }
    @Test fun uncertainOutcomePreservesKeyPayloadAndBlocksMutationAcrossRecreation() = runTest(dispatcher) {
        val handle = SavedStateHandle(); val vm = model(handle); runCurrent()
        vm.open(DetailsAction.Payment); val operation = handle.get<String>("operation")
        vm.submit(); runCurrent(); vm.change("amount","100"); vm.dismiss()
        assertTrue(vm.state.value.frozen); assertNotNull(vm.state.value.action)
        val restored = SavedStateHandle(mapOf("action" to handle.get<String>("action"),"fields" to handle.get<HashMap<String,String>>("fields"),
            "snapshot" to handle.get<ArrayList<String>>("snapshot"),"operation" to operation,"sent" to true))
        val next = model(restored); runCurrent()
        assertEquals(operation,restored.get<String>("operation")); assertEquals(vm.state.value.fields,next.state.value.fields)
        assertTrue(next.state.value.frozen)
    }
    @Test fun cancelledDeleteDoesNotExecuteAnything() = runTest(dispatcher) {
        var count = 0; val vm = model(operation = { count++ }); runCurrent()
        vm.open(DetailsAction.Delete); vm.dismiss(); runCurrent()
        assertNull(vm.state.value.action); assertEquals(0,count)
    }
}
