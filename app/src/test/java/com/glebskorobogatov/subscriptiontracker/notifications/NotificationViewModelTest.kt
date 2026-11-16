package com.glebskorobogatov.subscriptiontracker.notifications

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Controller policy tests. These do not claim to test an Android permission dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private class Environment : NotificationEnvironment {
        var value = NotificationAccess(false,false,true)
        var offers = 0; var reconciliations = 0
        override val runtimePermissionRequired = true
        override fun access() = value
        override fun generation() = UUID.fromString("11111111-1111-4111-8111-111111111111")
        override fun claimAutomaticOffer(): Boolean { offers++; return true }
        override suspend fun reconcile() { reconciliations++ }
        override fun dismiss(route: NotificationRoute) = Unit
    }
    private lateinit var environment: Environment
    @Before fun start(): Unit { Dispatchers.setMain(dispatcher); environment = Environment() }
    @After fun stop(): Unit { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private fun model(saved: SavedStateHandle = SavedStateHandle(), source: SubscriptionsSource = SubscriptionsSource { flowOf(SubscriptionsUiState.Empty()) }): NotificationViewModel {
        val owner = SubscriptionsViewModel(source)
        store.put("owner",owner)
        return NotificationViewModel(saved,owner,environment,io=dispatcher).also { store.put("notifications",it) }
    }
    @Test fun startupWithoutExplicitSaveNeverOffersPermission(): Unit = runTest(dispatcher) {
        val vm = model(); runCurrent(); repeat(3) { vm.refresh() }; runCurrent()
        assertFalse(vm.state.value.explanation); assertEquals(0,environment.offers); assertEquals(0,environment.reconciliations)
    }
    @Test fun grantingPermissionReconcilesOnceWithoutRepeatedRefreshRequests(): Unit = runTest(dispatcher) {
        val vm = model(); runCurrent()
        environment.value = NotificationAccess(true,true,true)
        vm.refresh(); runCurrent(); repeat(3) { vm.refresh() }; runCurrent()
        assertEquals(1,environment.reconciliations); assertFalse(vm.state.value.explanation)
    }
    @Test fun restoringAChannelReconcilesAndDisablingDoesNotPublish(): Unit = runTest(dispatcher) {
        val vm = model(); runCurrent()
        environment.value = NotificationAccess(true,true,false); vm.refresh(); runCurrent()
        assertEquals(0,environment.reconciliations)
        environment.value = environment.value.copy(paymentsEnabled=true); vm.refresh(); runCurrent()
        assertEquals(1,environment.reconciliations)
        environment.value = environment.value.copy(appEnabled=false); vm.refresh(); runCurrent()
        assertEquals(1,environment.reconciliations)
    }
    @Test fun explanationRestoresButOnlyOneUserConfirmationCanRequest(): Unit = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf("explanation" to true))
        val vm = model(saved); runCurrent(); assertTrue(vm.state.value.explanation)
        assertTrue(vm.confirmExplanation()); assertFalse(vm.confirmExplanation())
        assertEquals(false,saved.get<Boolean>("explanation")); assertEquals(0,environment.offers)
    }
    @Test fun decliningExplanationDoesNotRequestOrReconcile(): Unit = runTest(dispatcher) {
        val vm = model(SavedStateHandle(mapOf("explanation" to true))); runCurrent()
        vm.dismissExplanation(); runCurrent()
        assertFalse(vm.state.value.explanation); assertFalse(vm.confirmExplanation()); assertEquals(0,environment.reconciliations)
    }
    @Test fun malformedRouteHasOneSafeFallbackAndConsumptionClearsSavedRequest(): Unit = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = model(saved); runCurrent(); vm.open("https://external.invalid"); runCurrent()
        val navigation = vm.state.value.navigation!!
        assertNull(navigation.subscription); vm.consumed(navigation.sequence)
        assertNull(vm.state.value.navigation); assertFalse(saved.contains("pendingRoute"))
    }
    @Test fun oldGenerationIsRejectedBeforeAnyRoomLookup(): Unit = runTest(dispatcher) {
        var lookups = 0
        val vm = model(source = object : SubscriptionsSource {
            override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
            override suspend fun <T> perform(block: suspend (com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases) -> T): T {
                lookups++; error("must not read foreign generation")
            }
        })
        runCurrent(); vm.open(NotificationRoute(UUID.randomUUID(),UUID.randomUUID()).identity); runCurrent()
        assertEquals(0,lookups); assertNull(vm.state.value.navigation!!.subscription)
    }
    @Test fun staleOpenSessionIsReopenedBeforeLookupAndReadErrorIsNeverSuccess(): Unit = runTest(dispatcher) {
        var opened = UUID.randomUUID(); var reads = 0; var lookups = 0
        val vm = model(source = object : SubscriptionsSource {
            override fun activeDatasetId() = opened
            override fun observe(refreshes: Flow<Unit>) = flow {
                reads++; if (reads > 1) opened = environment.generation()
                emit(SubscriptionsUiState.Empty())
            }
            override suspend fun <T> perform(block: suspend (com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases) -> T): T {
                assertEquals(environment.generation(),opened); lookups++; error("temporary Room failure")
            }
        })
        runCurrent(); vm.open(NotificationRoute(environment.generation(),UUID.randomUUID()).identity); runCurrent()
        assertEquals(2,reads); assertEquals(1,lookups); assertNull(vm.state.value.navigation!!.subscription)
    }

    @Test fun duplicateIntentCannotReopenDetailsAfterBackDuringLookup(): Unit = runTest(dispatcher) {
        val gate = CompletableDeferred<Boolean>()
        var present = true
        val saved = SavedStateHandle()
        val id = UUID.randomUUID()
        val owner = SubscriptionsViewModel(object : SubscriptionsSource {
            override fun activeDatasetId() = environment.generation()
            override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
        })
        store.put("owner",owner)
        val vm = NotificationViewModel(saved,owner,environment,io=dispatcher,lookup={ gate.await(); present })
        store.put("notifications",vm); runCurrent()
        vm.visibleSubscription(id)
        vm.open(NotificationRoute(environment.generation(),id).identity); runCurrent()
        vm.visibleSubscription(null) // Back wins over a delayed duplicate intent.
        gate.complete(true); runCurrent()
        assertNull(vm.state.value.navigation); assertFalse(saved.contains("pendingRoute"))
        // A deleted target still falls back, even if its details had been visible.
        present = false; vm.visibleSubscription(id)
        vm.open(NotificationRoute(environment.generation(),id).identity); runCurrent()
        assertNotNull(vm.state.value.navigation); assertNull(vm.state.value.navigation!!.subscription)
    }

}
