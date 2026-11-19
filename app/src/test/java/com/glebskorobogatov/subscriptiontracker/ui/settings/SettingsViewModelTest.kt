package com.glebskorobogatov.subscriptiontracker.ui.settings

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val store = ViewModelStore(); private var number = 0
    @Before fun start() { Dispatchers.setMain(dispatcher) }
    @After fun stop() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private fun model(saved: SavedStateHandle = SavedStateHandle(),values: Flow<Settings> = flowOf(Settings()),
        dataset: () -> UUID? = { null }, operation: suspend () -> Unit = {}): SettingsViewModel {
        val owner = SubscriptionsViewModel(object: SubscriptionsSource {
            override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
            override fun settings() = values
            override fun activeDatasetId() = dataset()
            override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T { operation(); error("Unknown outcome") }
        }); store.put("owner${number++}",owner)
        return SettingsViewModel(saved,owner).also { store.put("settings${number++}",it) }
    }
    @Test fun loadingHasNoInventedDefaultsBeforeFirstRead() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val vm = model(values = flow { gate.await(); emit(Settings(theme = Theme.DARK)) })
        runCurrent(); assertNull(vm.state.value.data); assertEquals(SettingsPhase.Loading,vm.state.value.phase)
        gate.complete(Unit); runCurrent(); assertEquals(Theme.DARK,vm.state.value.data!!.theme)
    }
    @Test fun failedReadIsErrorAndRetryDoesNotResetStoredPreferences() = runTest(dispatcher) {
        var bad = true; val preferences = Settings(theme = Theme.DARK)
        val vm = model(values = flow { if(bad) error("read"); emit(preferences) }); runCurrent()
        assertNull(vm.state.value.data); assertEquals(SettingsPhase.Error,vm.state.value.phase)
        bad = false; vm.retryRead(); runCurrent(); assertEquals(preferences,vm.state.value.data)
    }
    @Test fun laterReadFailureKeepsLastKnownTheme() = runTest(dispatcher) {
        val bad = CompletableDeferred<Unit>(); val vm = model(values = flow { emit(Settings(theme = Theme.DARK)); bad.await(); error("read") })
        runCurrent(); bad.complete(Unit); runCurrent()
        assertEquals(SettingsPhase.Error,vm.state.value.phase); assertEquals(Theme.DARK,vm.state.value.data!!.theme)
    }
    @Test fun retiredTimeDialogAndIntentAreDiscardedWithoutSaving() = runTest(dispatcher) {
        var writes = 0
        val saved = SavedStateHandle(mapOf("timeOpen" to true,"hour" to 18,"minute" to 45,
            "intent" to hashMapOf("kind" to "time","value" to "18:45"),"other" to "preserve"))
        val vm = model(saved,operation = { writes++ }); runCurrent(); vm.retryWrite(); runCurrent()
        assertFalse(vm.state.value.pending); assertEquals(0,writes)
        assertFalse(saved.contains("intent")); assertFalse(saved.contains("timeOpen"))
        assertEquals("preserve",saved.get<String>("other"))
    }

    @Test fun retiredCancellationIntentCannotBeRetried() = runTest(dispatcher) {
        var writes = 0
        val saved = SavedStateHandle(mapOf("intent" to hashMapOf("kind" to "cancellation","day" to "7","enabled" to "true")))
        val vm = model(saved,operation = { writes++ }); runCurrent(); vm.retryWrite(); runCurrent()
        assertFalse(vm.state.value.pending); assertEquals(0,writes); assertFalse(saved.contains("intent"))
    }

    @Test fun uncertainWriteKeepsIntentAndRestorationNeverResubmitsIt() = runTest(dispatcher) {
        var writes = 0; val saved = SavedStateHandle(); val vm = model(saved,operation = { writes++ }); runCurrent()
        vm.setTheme(Theme.DARK); runCurrent(); assertTrue(vm.state.value.pending); assertNotNull(vm.state.value.error)
        val intent = saved.get<HashMap<String,String>>("intent")!!
        val next = model(SavedStateHandle(mapOf("intent" to intent)),operation = { writes++ }); runCurrent()
        assertEquals(1,writes); next.setTheme(Theme.LIGHT); runCurrent(); assertEquals(1,writes)
        next.retryWrite(); runCurrent(); assertEquals(2,writes)
        next.retryRead(); runCurrent(); assertFalse(next.state.value.pending)
    }
    @Test fun busyBlocksDoubleWriteAndChanges() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); var writes = 0
        val vm = model(operation = { writes++; gate.await() }); runCurrent()
        vm.setTheme(Theme.DARK); vm.setTheme(Theme.LIGHT); vm.retryWrite(); runCurrent()
        assertEquals(1,writes); assertEquals(SettingsPhase.Saving,vm.state.value.phase)
        gate.complete(Unit); runCurrent()
    }
    @Test fun malformedOrUnknownPendingPreferenceNeverWritesOnRestoreOrRetry() = runTest(dispatcher) {
        var writes = 0
        for (intent in listOf(hashMapOf("kind" to "payment","day" to "99","enabled" to "true"),
            hashMapOf("kind" to "currency","value" to "USD"),hashMapOf("kind" to "horizon","value" to "6"))) {
            val saved = SavedStateHandle(mapOf("intent" to intent)); val vm = model(saved,operation = { writes++ })
            runCurrent(); vm.retryWrite(); runCurrent(); assertFalse(vm.state.value.pending)
        }
        assertEquals(0,writes)
    }

    @Test fun preferencePatchesAreIndependentIdempotentAndPreserveOtherFields() {
        val original = Settings(theme = Theme.DARK)
        val patch = mapOf("kind" to "payment","day" to "7","enabled" to "true")
        val once = applySetting(original,patch)
        assertEquals(once,applySetting(once,patch)); assertEquals(setOf(7,3,1),once.renewalOffsets)
        assertEquals(original.copy(renewalOffsets = once.renewalOffsets),once)
        var empty = once
        for(day in listOf(7,3,1,0)) empty = applySetting(empty,mapOf("kind" to "payment","day" to day.toString(),"enabled" to "false"))
        assertTrue(empty.renewalOffsets.isEmpty())
    }

    @Test fun animationAssignmentIsExplicitIdempotentAndKeepsThemeAndOffsets() {
        val original = Settings(theme = Theme.DARK,renewalOffsets = setOf(7,0),animationsEnabled = true)
        val off = mapOf("kind" to "animations","value" to "false")
        val disabled = applySetting(original,off)
        assertFalse(disabled.animationsEnabled)
        assertEquals(disabled,applySetting(disabled,off))
        assertEquals(Theme.DARK,disabled.theme)
        assertEquals(setOf(7,0),disabled.renewalOffsets)
        val themed = applySetting(disabled,mapOf("kind" to "theme","value" to "LIGHT"))
        assertEquals(Theme.LIGHT,themed.theme); assertFalse(themed.animationsEnabled)
        val offsets = applySetting(themed,mapOf("kind" to "payment","day" to "3","enabled" to "true"))
        assertEquals(setOf(7,0,3),offsets.renewalOffsets); assertFalse(offsets.animationsEnabled)
        assertTrue(applySetting(offsets,mapOf("kind" to "animations","value" to "true")).animationsEnabled)
    }

    @Test fun pendingAnimationIntentRestoresWithoutRewritingUntilRetry() = runTest(dispatcher) {
        var writes = 0
        val saved = SavedStateHandle(mapOf("intent" to hashMapOf("kind" to "animations","value" to "false")))
        val vm = model(saved,operation = { writes++ }); runCurrent()
        assertTrue(vm.state.value.pending); assertEquals(0,writes)
        vm.setAnimations(true); runCurrent(); assertEquals(0,writes)
        vm.retryWrite(); runCurrent(); assertEquals(1,writes)
        assertEquals("false",saved.get<HashMap<String,String>>("intent")?.get("value"))
    }

    @Test fun failedAnimationWriteKeepsTheSameIntentForRetry() = runTest(dispatcher) {
        var writes = 0
        val saved = SavedStateHandle(); val vm = model(saved,operation = { writes++ }); runCurrent()
        vm.setAnimations(false); runCurrent()
        assertEquals(1,writes); assertTrue(vm.state.value.pending)
        assertEquals("false",saved.get<HashMap<String,String>>("intent")?.get("value"))
        vm.retryWrite(); runCurrent(); assertEquals(2,writes)
        assertEquals("false",saved.get<HashMap<String,String>>("intent")?.get("value"))
    }

    @Test fun rapidAnimationChangesKeepOnlyTheInFlightAssignment() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); var writes = 0
        val vm = model(operation = { writes++; gate.await() }); runCurrent()
        vm.setAnimations(false); vm.setAnimations(true); vm.setTheme(Theme.DARK); runCurrent()
        assertEquals(1,writes); assertEquals(SettingsPhase.Saving,vm.state.value.phase)
        gate.complete(Unit); runCurrent()
    }

    @Test fun malformedAnimationIntentIsDiscarded() = runTest(dispatcher) {
        var writes = 0
        val saved = SavedStateHandle(mapOf("intent" to hashMapOf("kind" to "animations","value" to "yes")))
        val vm = model(saved,operation = { writes++ }); runCurrent(); vm.retryWrite(); runCurrent()
        assertEquals(0,writes); assertFalse(vm.state.value.pending); assertFalse(saved.contains("intent"))
    }

    @Test fun rankingWriteFailureRetriesTheSameAssignmentAndCannotCrossDataset() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        var writes = 0
        var dataset = UUID(0, 1)
        val existing = Settings(theme = Theme.DARK, animationsEnabled = false, renewalOffsets = setOf(0))
        val vm = model(saved, flowOf(existing), dataset = { dataset }, operation = { writes++ })
        runCurrent()
        vm.setCostRankingPeriod(CostRankingPeriod.YEAR)
        runCurrent()
        assertEquals(existing, vm.state.value.data)
        assertTrue(vm.state.value.rankingPending)
        val pending = requireNotNull(saved.get<HashMap<String,String>>("intent"))
        assertEquals("YEAR", pending["value"])
        assertEquals(dataset.toString(), pending["dataset"])
        vm.retryWrite()
        runCurrent()
        assertEquals(2, writes)
        assertEquals(pending, saved.get<HashMap<String,String>>("intent"))
        val assigned = applySetting(existing, pending)
        assertEquals(existing.copy(costRankingPeriod = CostRankingPeriod.YEAR), assigned)
        assertEquals(assigned, applySetting(assigned, pending))

        dataset = UUID(0, 2)
        val restored = model(SavedStateHandle(mapOf("intent" to pending)), flowOf(existing),
            dataset = { dataset }, operation = { writes++ })
        runCurrent()
        restored.retryWrite()
        vm.retryWrite()
        runCurrent()
        assertEquals(2, writes)
        assertFalse(restored.state.value.pending)
        assertFalse(vm.state.value.rankingPending)
        assertEquals(CostRankingPeriod.MONTH, restored.state.value.data?.costRankingPeriod)
    }

}
