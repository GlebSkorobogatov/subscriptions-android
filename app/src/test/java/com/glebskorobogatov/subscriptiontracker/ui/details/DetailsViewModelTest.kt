package com.glebskorobogatov.subscriptiontracker.ui.details

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.SubscriptionSnapshot
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
    private val detail = SubscriptionDetails(detailsRow(),LifecycleView(VisibleStatus.ACTIVE,null,null))
    private fun model(handle: SavedStateHandle = SavedStateHandle(), values: Flow<SubscriptionDetails?> = flowOf(detail),
        generation: UUID? = null, operation: suspend () -> Unit = { error("Unknown outcome") }): DetailsViewModel {
        val owner = SubscriptionsViewModel(object : SubscriptionsSource {
            override fun activeDatasetId() = generation
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
        vm.open(DetailsAction.Archive); vm.submit(); vm.submit(); runCurrent()
        assertEquals(DetailsPhase.OperationInProgress,vm.state.value.phase); assertEquals(1,count)
        gate.complete(Unit); runCurrent(); assertTrue(vm.state.value.frozen)
    }
    @Test fun uncertainOutcomePreservesKeyPayloadAndBlocksMutationAcrossRecreation() = runTest(dispatcher) {
        val handle = SavedStateHandle(); val vm = model(handle); runCurrent()
        vm.open(DetailsAction.Archive); val snapshot = handle.get<ArrayList<String>>("snapshot")
        vm.submit(); runCurrent(); vm.change("amount","100"); vm.dismiss()
        assertTrue(vm.state.value.frozen); assertNotNull(vm.state.value.action)
        val restored = SavedStateHandle(mapOf("action" to handle.get<String>("action"),"fields" to handle.get<HashMap<String,String>>("fields"),
            "snapshot" to handle.get<ArrayList<String>>("snapshot"),"sent" to true))
        val next = model(restored); runCurrent()
        assertEquals(snapshot,restored.get<ArrayList<String>>("snapshot")); assertEquals(vm.state.value.fields,next.state.value.fields)
        assertTrue(next.state.value.frozen)
    }
    @Test fun cancelledDeleteDoesNotExecuteAnything() = runTest(dispatcher) {
        var count = 0; val vm = model(operation = { count++ }); runCurrent()
        vm.open(DetailsAction.Delete); vm.dismiss(); runCurrent()
        assertNull(vm.state.value.action); assertEquals(0,count)
    }
    @Test fun removedAndUnknownSavedActionsAreDiscardedWithoutExecutionOrClearingOtherState() = runTest(dispatcher) {
        var calls = 0
        for (action in listOf("Payment","MarkIssue","AddPayment","AddRefund","Unknown")) {
            val saved = SavedStateHandle(mapOf("action" to action,"operation" to UUID.randomUUID().toString(),
                "snapshot" to SubscriptionSnapshot.snapshot(detailsRow()),"sent" to true,
                "fields" to hashMapOf("amount" to "10"),"unrelatedDraft" to "preserve"))
            val vm = model(saved,operation = { calls++ }); runCurrent()
            assertEquals(DetailsPhase.Content,vm.state.value.phase); assertNull(vm.state.value.action)
            assertFalse(vm.state.value.frozen); vm.submit(); runCurrent()
            assertFalse(saved.contains("snapshot")); assertFalse(saved.contains("operation"))
            assertEquals("preserve",saved.get<String>("unrelatedDraft"))
        }
        assertEquals(0,calls)
    }
    @Test fun malformedOrWrongSubscriptionSnapshotCannotRestoreAnAction() = runTest(dispatcher) {
        for (snapshot in listOf(arrayListOf("broken"),SubscriptionSnapshot.snapshot(detailsRow().copy(id = UUID(0,42))))) {
            val saved = SavedStateHandle(mapOf("action" to "Delete","snapshot" to snapshot,"sent" to true))
            val vm = model(saved); runCurrent(); vm.submit(); runCurrent()
            assertNull(vm.state.value.action); assertFalse(saved.contains("snapshot"))
        }
    }
    @Test fun compatibleDraftRetainsCompleteExpectedSnapshotAndDoesNotSubmitOnRestore() = runTest(dispatcher) {
        var calls = 0
        val snapshot = SubscriptionSnapshot.snapshot(detailsRow())
        val saved = SavedStateHandle(mapOf("action" to "Disable","snapshot" to snapshot,
            "fields" to hashMapOf("end" to "2027-02-28"),"sent" to false))
        val vm = model(saved,operation = { calls++ }); runCurrent()
        assertEquals(DetailsAction.Disable,vm.state.value.action)
        assertEquals("2027-02-28",vm.state.value.fields["end"])
        assertEquals(snapshot,saved.get<ArrayList<String>>("snapshot")); assertEquals(0,calls)
    }
    @Test fun restoredGenerationMismatchBlocksMutationBeforePerform() = runTest(dispatcher) {
        var calls = 0
        val saved = SavedStateHandle(mapOf("action" to "Archive","snapshot" to SubscriptionSnapshot.snapshot(detailsRow()),
            "generation" to UUID(0,1).toString()))
        val vm = model(saved,generation = UUID(0,2),operation = { calls++ }); runCurrent()
        vm.submit(); runCurrent(); assertTrue(vm.state.value.conflicted); assertEquals(0,calls)
    }
    @Test fun legacyModesAndStatesLoadAndOnlyExistingIssueCanBeResolved() = runTest(dispatcher) {
        for (row in listOf(detailsRow().copy(renewalMode = RenewalMode.MANUAL),
            detailsRow().copy(lifecycleState = LifecycleState.PAYMENT_ISSUE),
            detailsRow().copy(lifecycleState = LifecycleState.CANCELLATION_SCHEDULED))) {
            val vm = model(values = flowOf(detail.copy(subscription = row))); runCurrent()
            assertEquals(DetailsPhase.Content,vm.state.value.phase)
            vm.open(DetailsAction.ResolveDate)
            assertEquals(if (row.lifecycleState == LifecycleState.PAYMENT_ISSUE) DetailsAction.ResolveDate else null,vm.state.value.action)
        }
    }
    @Test fun cancellationDoesNotBecomeUnknownOutcomeErrorOrTriggerRetry() = runTest(dispatcher) {
        var calls = 0
        val vm = model(operation = { calls++; throw CancellationException("Synthetic cancellation") }); runCurrent()
        vm.open(DetailsAction.Archive); vm.submit(); runCurrent()
        assertEquals(1,calls); assertNull(vm.state.value.error)
    }

    @Test fun savedReactivationCurrencyChangeIsDiscardedWithoutExecuting() = runTest(dispatcher) {
        var calls = 0
        val archived = detailsRow().copy(lifecycleState = LifecycleState.ARCHIVED)
        val saved = SavedStateHandle(mapOf("action" to "Reactivate","snapshot" to SubscriptionSnapshot.snapshot(archived),
            "fields" to hashMapOf("currency" to "USD","amount" to "10"),"sent" to true))
        val vm = model(saved,operation = { calls++ }); runCurrent(); vm.submit(); runCurrent()
        assertNull(vm.state.value.action); assertFalse(saved.contains("snapshot")); assertEquals(0,calls)
    }

}
