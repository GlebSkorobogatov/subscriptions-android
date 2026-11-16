package com.glebskorobogatov.subscriptiontracker.ui.settings

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.LocalTime
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
    private fun model(saved: SavedStateHandle = SavedStateHandle(),values: Flow<Settings> = flowOf(Settings()),operation: suspend () -> Unit = {}): SettingsViewModel {
        val owner = SubscriptionsViewModel(object: SubscriptionsSource {
            override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
            override fun settings() = values
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
        assertEquals(once,applySetting(once,patch)); assertEquals(setOf(7,3,1),once.paymentOffsetsDefault)
        assertEquals(original.copy(paymentOffsetsDefault = once.paymentOffsetsDefault),once)
        var empty = once
        for(day in listOf(7,3,1,0)) empty = applySetting(empty,mapOf("kind" to "payment","day" to day.toString(),"enabled" to "false"))
        assertTrue(empty.paymentOffsetsDefault.isEmpty())
    }
}
