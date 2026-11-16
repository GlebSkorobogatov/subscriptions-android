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
        var bad = true; val preferences = Settings(theme = Theme.DARK,reminderLocalTime = LocalTime.of(18,45))
        val vm = model(values = flow { if(bad) error("read"); emit(preferences) }); runCurrent()
        assertNull(vm.state.value.data); assertEquals(SettingsPhase.Error,vm.state.value.phase)
        bad = false; vm.retryRead(); runCurrent(); assertEquals(preferences,vm.state.value.data)
    }
    @Test fun laterReadFailureKeepsLastKnownTheme() = runTest(dispatcher) {
        val bad = CompletableDeferred<Unit>(); val vm = model(values = flow { emit(Settings(theme = Theme.DARK)); bad.await(); error("read") })
        runCurrent(); bad.complete(Unit); runCurrent()
        assertEquals(SettingsPhase.Error,vm.state.value.phase); assertEquals(Theme.DARK,vm.state.value.data!!.theme)
    }
    @Test fun timeDialogAndDraftRestoreWithoutSavingAutomatically() = runTest(dispatcher) {
        val saved = SavedStateHandle(); var writes = 0; val vm = model(saved,operation = { writes++ }); runCurrent()
        vm.openTime(); assertEquals(9,vm.state.value.hour); assertEquals(0,vm.state.value.minute)
        vm.timeDraft(18,45)
        val next = model(SavedStateHandle(mapOf("timeOpen" to true,"hour" to 18,"minute" to 45)),operation = { writes++ }); runCurrent()
        assertTrue(next.state.value.timeOpen); assertEquals(45,next.state.value.minute); assertEquals(0,writes)
        next.dismissTime(); assertEquals(0,writes)
    }
    @Test fun invalidTimeDoesNotStartWrite() = runTest(dispatcher) {
        var writes = 0; val vm = model(operation = { writes++ }); runCurrent(); vm.openTime(); vm.confirmTime(24,60); runCurrent()
        assertNotNull(vm.state.value.timeError); assertEquals(0,writes); assertTrue(vm.state.value.timeOpen)
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
    @Test fun rawTimeDraftSurvivesRestoreAndInvalidInputNeverWrites() = runTest(dispatcher) {
        var writes = 0; val saved = SavedStateHandle(); val vm = model(saved,operation = { writes++ }); runCurrent()
        vm.openTime(); vm.changeTimeInput(true,""); vm.changeTimeInput(false,"75")
        vm.confirmTimeInput(); runCurrent(); assertEquals(0,writes); assertNotNull(vm.state.value.timeError)
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val next = model(restored,operation = { writes++ }); runCurrent()
        assertEquals("",next.state.value.hourInput); assertEquals("75",next.state.value.minuteInput); assertTrue(next.state.value.timeOpen)
        next.changeTimeInput(true,"18"); next.changeTimeInput(false,"45"); next.confirmTimeInput(); runCurrent()
        assertEquals(1,writes); assertEquals("18:45",restored.get<HashMap<String,String>>("intent")!!["value"])
    }
    @Test fun preferencePatchesAreIndependentIdempotentAndPreserveOtherFields() {
        val original = Settings(theme = Theme.DARK,reminderLocalTime = LocalTime.of(18,45),chartHorizonMonths = 12)
        val patch = mapOf("kind" to "payment","day" to "7","enabled" to "true")
        val once = applySetting(original,patch)
        assertEquals(once,applySetting(once,patch)); assertEquals(setOf(7,3,1),once.paymentOffsetsDefault)
        assertEquals(original.cancellationOffsetsDefault,once.cancellationOffsetsDefault)
        assertEquals(original.copy(paymentOffsetsDefault = once.paymentOffsetsDefault),once)
        var empty = once
        for(day in listOf(7,3,1,0)) empty = applySetting(empty,mapOf("kind" to "payment","day" to day.toString(),"enabled" to "false"))
        assertTrue(empty.paymentOffsetsDefault.isEmpty()); assertEquals(original.cancellationOffsetsDefault,empty.cancellationOffsetsDefault)
        assertEquals(LocalTime.of(7,30),applySetting(original,mapOf("kind" to "time","value" to "07:30")).reminderLocalTime)
    }
}
