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
    private fun editor(handle: SavedStateHandle = SavedStateHandle(),today: java.time.LocalDate = java.time.LocalDate.now()): EditorViewModel {
        val owner = SubscriptionsViewModel(object: SubscriptionsSource {
            override fun today() = today
            override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
        })
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
        assertEquals("",vm.state.value.fields["name"])
        assertFalse(vm.state.value.dirty); assertFalse(saved.contains("fields"))
        assertFalse(vm.state.value.done)

    }
    @Test fun compatibleRubDraftRestoresAndRetiredFieldChangesAreIgnored() = runTest(dispatcher) {
        val fields = hashMapOf("name" to "Черновик","amount" to "1,23")
        val vm = editor(SavedStateHandle(mapOf("fields" to fields,"draftVersion" to 5))); runCurrent()
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
    @Test fun selectingUnchangedDateOrFrequencyPreservesAnchorAndPeriodDraft() = runTest(dispatcher) {
        val fields=hashMapOf("name" to "Черновик","amount" to "1,23","unit" to "MONTH","count" to "1","date" to "2027-02-28","anchorDay" to "31","start" to "2027-02-01","periodDue" to "2027-02-28")
        val vm=editor(SavedStateHandle(mapOf("fields" to fields,"draftVersion" to 5))); runCurrent()
        for (key in listOf("date","unit","count")) vm.change(key,fields.getValue(key))
        assertEquals(fields,vm.state.value.fields)
        vm.change("date","2027-03-02")
        for (key in listOf("anchorDay","start","periodDue")) assertFalse(key in vm.state.value.fields)
    }
    @Test fun editingExpiredPeriodStartScopesNewDraftToDisplayedCurrentDueWithoutReanchor() = runTest(dispatcher) {
        val fields=hashMapOf("name" to "Сервис","amount" to "1,23","unit" to "MONTH","count" to "1","date" to "2027-02-28","anchorDay" to "31","start" to "2027-01-01","periodDue" to "2027-01-31")
        val vm=editor(SavedStateHandle(mapOf("fields" to fields,"draftVersion" to 5)),java.time.LocalDate.of(2027,2,15)); runCurrent()
        vm.change("start","2027-02-01")
        assertEquals("2027-02-28",vm.state.value.fields["periodDue"])
        assertEquals("31",vm.state.value.fields["anchorDay"])
        assertFalse("start" in vm.validate(vm.state.value.fields))
        vm.change("start","")
        assertFalse("periodDue" in vm.state.value.fields)
    }
}
