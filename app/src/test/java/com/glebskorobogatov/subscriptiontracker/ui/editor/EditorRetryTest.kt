package com.glebskorobogatov.subscriptiontracker.ui.editor

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class EditorRetryTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun start() { Dispatchers.setMain(dispatcher) }
    @After fun stop() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    @Test fun retryRestartsFailedSessionRatherThanReadingClosedRepositoriesAgain() = runTest(dispatcher) {
        var opens = 0
        val owner = SubscriptionsViewModel { flow { opens++; error("Cannot open Room") } }
        store.put("owner",owner)
        val vm = EditorViewModel(SavedStateHandle(),owner,UUID.randomUUID()); store.put("editor",vm)
        runCurrent(); val before = opens
        vm.reload(false); runCurrent()
        assertEquals(before + 1,opens)
    }
    @Test fun initialReadErrorRetainsSavedDraft() = runTest(dispatcher) {
        val fields = hashMapOf("name" to "Несохранённая подписка","amount" to "599")
        val owner = SubscriptionsViewModel { flow { error("Cannot open Room") } }; store.put("owner",owner)
        val vm = EditorViewModel(SavedStateHandle(mapOf("fields" to fields,"draftVersion" to 5)),owner,UUID.randomUUID()); store.put("editor",vm)
        runCurrent(); assertNotNull(vm.state.value.error); assertEquals(fields,vm.state.value.fields)
    }
}
