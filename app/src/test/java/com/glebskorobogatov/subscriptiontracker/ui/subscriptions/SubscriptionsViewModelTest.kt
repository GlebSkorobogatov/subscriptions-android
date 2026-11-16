package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private fun model(source: SubscriptionsSource) = SubscriptionsViewModel(source).also { store.put("screen", it) }

    @Test fun loadingWaitsForFirstEmission() = runTest(dispatcher) {
        val result = CompletableDeferred<SubscriptionsUiState>()
        val vm = model { flow { emit(result.await()) } }
        runCurrent()
        assertEquals(SubscriptionsUiState.Loading, vm.state.value)
        result.complete(SubscriptionsUiState.Empty())
        runCurrent()
        assertEquals(SubscriptionsUiState.Empty(), vm.state.value)
    }
    @Test fun readFailureIsErrorAndRetryCanRecover() = runTest(dispatcher) {
        var attempts = 0
        val vm = model { flow {
            if (++attempts == 1) throw IllegalStateException("Room read failed")
            emit(SubscriptionsUiState.Empty())
        } }
        runCurrent(); assertEquals(SubscriptionsUiState.Error, vm.state.value)
        vm.retry(); runCurrent()
        assertEquals(SubscriptionsUiState.Empty(), vm.state.value)
        assertEquals(2, attempts)
    }
    @Test fun overflowNeverProducesEmptyOrPartialTotals() = runTest(dispatcher) {
        val vm = model { flow { throw ArithmeticException("overflow") } }
        runCurrent(); assertEquals(SubscriptionsUiState.Error, vm.state.value)
    }
    @Test fun retryWaitsForPreviousResourceCleanup() = runTest(dispatcher) {
        var opened = 0; var live = 0; var closed = 0
        val vm = model { flow {
            assertEquals(0, live); live++; opened++
            try { emit(SubscriptionsUiState.Empty()); awaitCancellation() }
            finally { withContext(NonCancellable) { delay(100); live--; closed++ } }
        } }
        runCurrent(); vm.retry(); runCurrent()
        assertEquals(1, opened)
        advanceTimeBy(101); runCurrent()
        assertEquals(2, opened); assertEquals(1, closed)
        store.clear(); advanceUntilIdle()
        assertEquals(0, live); assertEquals(2, closed)
    }
    @Test fun resumeIsDeliveredToCurrentSessionWithoutReopening() = runTest(dispatcher) {
        var opened = 0; var refreshes = 0
        val vm = model { events -> flow {
            opened++
            events.collect { refreshes++; emit(SubscriptionsUiState.Empty()) }
        } }
        runCurrent(); vm.refreshToday(); runCurrent(); vm.refreshToday(); runCurrent()
        assertEquals(1, opened); assertEquals(2, refreshes)
    }
}
