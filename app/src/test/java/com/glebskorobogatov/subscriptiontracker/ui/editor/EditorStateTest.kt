package com.glebskorobogatov.subscriptiontracker.ui.editor

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class EditorStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private fun editor(handle: SavedStateHandle = SavedStateHandle()): EditorViewModel {
        val owner = SubscriptionsViewModel { flowOf(SubscriptionsUiState.Empty()) }
        store.put("owner",owner)
        return EditorViewModel(handle,owner,null).also { store.put("editor",it) }
    }
    @Test fun invalidFieldsPreventOperation() = runTest(dispatcher) {
        val vm = editor(); runCurrent(); vm.save(); runCurrent()
        assertTrue("name" in vm.state.value.errors)
        assertTrue("amount" in vm.state.value.errors)
        assertFalse(vm.state.value.saving)
    }
    @Test fun savedDraftRestoresAllValues() = runTest(dispatcher) {
        val fields = hashMapOf("name" to "Черновик","amount" to "1,234","currency" to "KWD","PAYMENT" to "","CANCELLATION" to "7,3")
        val vm = editor(SavedStateHandle(mapOf("fields" to fields)))
        runCurrent(); assertEquals(fields,vm.state.value.fields); assertTrue(vm.state.value.dirty)
    }
    @Test fun failedSaveRetainsUserInput() = runTest(dispatcher) {
        val vm = editor(); runCurrent()
        vm.change("name","Черновик"); vm.change("amount","12,30")
        vm.save(); runCurrent()
        assertEquals("Черновик",vm.state.value.fields["name"])
        assertNotNull(vm.state.value.error); assertFalse(vm.state.value.done)
        assertFalse(vm.state.value.saving)
    }
}
