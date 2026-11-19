package com.glebskorobogatov.subscriptiontracker.ui.details

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.SubscriptionSnapshot
import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
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
    private val detail = SubscriptionDetails(detailsRow(),SubscriptionPeriod(null,null,null))
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
    @Test fun conflictedDeleteRefreshesTheSnapshotBeforeAnotherConfirmation() = runTest(dispatcher) {
        val rows = MutableStateFlow<SubscriptionDetails?>(detail)
        val saved = SavedStateHandle()
        var calls = 0
        val vm = model(saved, rows, operation = {
            calls++
            throw com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict()
        })
        runCurrent()
        vm.open(DetailsAction.Delete)
        vm.submit()
        runCurrent()
        assertTrue(vm.state.value.conflicted)
        assertFalse(vm.state.value.deleted)
        vm.submit()
        runCurrent()
        assertEquals(1, calls)
        val changed = detail.copy(subscription = detail.subscription.copy(name = "Новый снимок"))
        rows.value = changed
        vm.refresh()
        runCurrent()
        assertNull(vm.state.value.action)
        assertFalse(vm.state.value.conflicted)
        assertFalse(vm.state.value.deleted)
        assertEquals(changed, vm.state.value.data)
        assertEquals(1, calls)
        vm.open(DetailsAction.Delete)
        assertEquals(changed.subscription, SubscriptionSnapshot.row(requireNotNull(saved["snapshot"])))
    }

    @Test fun uncertainDeleteCanReloadEvenWhenTheRowIsAlreadyGone() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val rows = MutableStateFlow<SubscriptionDetails?>(detail)
        var calls = 0
        val vm = model(values = rows, operation = {
            calls++
            gate.await()
            rows.value = null
            error("Ответ после записи не получен")
        })
        runCurrent()
        vm.open(DetailsAction.Delete)
        vm.submit()
        runCurrent()
        vm.refresh()
        vm.dismiss()
        vm.submit()
        runCurrent()
        assertEquals(DetailsPhase.OperationInProgress, vm.state.value.phase)
        assertEquals(1, calls)
        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.frozen)
        assertFalse(vm.state.value.deleted)
        vm.refresh()
        runCurrent()
        assertEquals(DetailsPhase.NotFound, vm.state.value.phase)
        assertNull(vm.state.value.action)
        assertFalse(vm.state.value.frozen)
        assertFalse(vm.state.value.deleted)
        assertEquals(1, calls)
    }

    @Test fun readFailureDoesNotUnlockANonterminalDelete() = runTest(dispatcher) {
        val write = CompletableDeferred<Unit>()
        val failRead = CompletableDeferred<Unit>()
        var calls = 0
        val vm = model(values = flow {
            emit(detail)
            failRead.await()
            error("Ошибка наблюдения")
        }, operation = { calls++; write.await() })
        runCurrent()
        vm.open(DetailsAction.Delete)
        vm.submit()
        runCurrent()
        failRead.complete(Unit)
        runCurrent()
        assertEquals(DetailsPhase.OperationInProgress, vm.state.value.phase)
        vm.submit()
        runCurrent()
        assertEquals(1, calls)
        write.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.frozen)
    }

    @Test fun incompatibleAnchorIsReportedBeforeMutationAndCorrectionCanSubmit() = runTest(dispatcher) {
        var calls = 0
        val archived = detail.copy(subscription = detail.subscription.copy(state = SubscriptionState.ARCHIVED))
        val vm = model(values = flowOf(archived), operation = { calls++ })
        runCurrent()
        vm.open(DetailsAction.Reactivate)
        vm.change("date", "2027-02-28")
        vm.changeFrequency("DAY", "1")
        vm.change("anchorDay", "31")
        vm.submit()
        runCurrent()
        assertEquals(com.glebskorobogatov.subscriptiontracker.R.string.details_anchor_invalid, vm.state.value.error)
        assertEquals(0, calls)
        vm.changeFrequency("MONTH", "1")
        vm.submit()
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun removedAndUnknownSavedActionsAreDiscardedWithoutExecutionOrClearingOtherState() = runTest(dispatcher) {
        var calls = 0
        for (action in listOf("Payment","MarkIssue","AddPayment","AddRefund","ResolveDate","Unknown")) {
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
    @Test fun onlyTargetStatesLoadAndCancelledEndEditingIsContextual() = runTest(dispatcher) {
        for (status in SubscriptionState.entries) {
            val row=detailsRow().copy(state=status)
            val vm=model(values=flowOf(detail.copy(subscription=row))); runCurrent()
            assertEquals(DetailsPhase.Content,vm.state.value.phase)
            vm.open(DetailsAction.EditEnd)
            assertEquals(if (status==SubscriptionState.CANCELLED) DetailsAction.EditEnd else null,vm.state.value.action)
        }
    }
    @Test fun customFrequencySurvivesRecreationAndDoesNotCollapseAPresetMatch() = runTest(dispatcher) {
        val row = detailsRow().copy(state = SubscriptionState.ARCHIVED, intervalUnit = FrequencyUnit.MONTH, intervalCount = 6)
        val vm = model(values = flowOf(detail.copy(subscription = row))); runCurrent()
        vm.open(DetailsAction.Reactivate)
        assertNull(vm.state.value.customFrequency)
        assertEquals("6", vm.state.value.fields["count"])
        vm.setFrequencyMode(true)
        vm.changeFrequency("MONTH", "1")
        assertEquals(true, vm.state.value.customFrequency)
        assertEquals("1", vm.state.value.fields["count"])
        assertEquals("MONTH", vm.state.value.fields["unit"])
        val saved = SavedStateHandle(mapOf(
            "action" to "Reactivate",
            "fields" to HashMap(vm.state.value.fields),
            "snapshot" to SubscriptionSnapshot.snapshot(row),
            "frequencyCustom" to true,
            "sent" to false))
        val restored = model(saved, values = flowOf(detail.copy(subscription = row))); runCurrent()
        assertEquals(true, restored.state.value.customFrequency)
        assertEquals("MONTH", restored.state.value.fields["unit"])
        assertEquals("1", restored.state.value.fields["count"])
        restored.setFrequencyMode(false)
        restored.changeFrequency("MONTH", "3")
        assertEquals(false, restored.state.value.customFrequency)
        assertEquals("3", restored.state.value.fields["count"])
    }
    @Test fun dismissKeepsArchivedFlagClearAndBlocksFrozenMutation() = runTest(dispatcher) {
        val vm = model(); runCurrent()
        vm.open(DetailsAction.Archive); vm.dismiss(); runCurrent()
        assertFalse(vm.state.value.archived); assertNull(vm.state.value.action)
    }
    @Test fun cancellationDoesNotBecomeUnknownOutcomeErrorOrTriggerRetry() = runTest(dispatcher) {
        var calls = 0
        val vm = model(operation = { calls++; throw CancellationException("Synthetic cancellation") }); runCurrent()
        vm.open(DetailsAction.Archive); vm.submit(); runCurrent()
        assertEquals(1,calls); assertNull(vm.state.value.error)
        assertTrue(vm.state.value.frozen)
        assertEquals(DetailsPhase.Content, vm.state.value.phase)
    }


}
