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
    @Test fun legacyDraftDropsRetiredRulesAndRequiresForeignAmountReentry() = runTest(dispatcher) {
        val fields = hashMapOf("name" to "Черновик","amount" to "1,234","currency" to "KWD",
            "PAYMENT" to "","CANCELLATION" to "7,3","lead" to "7")
        val saved = SavedStateHandle(mapOf("fields" to fields))
        val vm = editor(saved); runCurrent()
        assertEquals("Черновик",vm.state.value.fields["name"])
        assertEquals("RUB",vm.state.value.fields["currency"]); assertEquals("",vm.state.value.fields["amount"])
        for (key in listOf("PAYMENT","CANCELLATION","lead")) assertFalse(key in vm.state.value.fields)
        assertEquals(vm.state.value.fields,saved.get<HashMap<String,String>>("fields")); assertTrue(vm.state.value.dirty)
        vm.save(); runCurrent(); assertTrue("amount" in vm.state.value.errors); assertFalse(vm.state.value.done)
    }
    @Test fun compatibleRubDraftRestoresAndRetiredFieldChangesAreIgnored() = runTest(dispatcher) {
        val fields = hashMapOf("name" to "Черновик","amount" to "1,23","currency" to "RUB")
        val vm = editor(SavedStateHandle(mapOf("fields" to fields))); runCurrent()
        assertEquals(fields,vm.state.value.fields)
        for (key in listOf("currency","PAYMENT","CANCELLATION","lead")) vm.change(key,"obsolete")
        assertEquals(fields,vm.state.value.fields)
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
