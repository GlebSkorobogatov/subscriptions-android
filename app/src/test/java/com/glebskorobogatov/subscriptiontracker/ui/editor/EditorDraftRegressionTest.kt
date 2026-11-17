package com.glebskorobogatov.subscriptiontracker.ui.editor

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.toEntity
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Loaded-row fixture follows EditorScheduleValidationTest; writes are covered on Android. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorDraftRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val today = LocalDate.of(2027,2,15)
    private var index = 0
    @Before fun open() { Dispatchers.setMain(dispatcher) }
    @After fun close() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private fun model(saved: SavedStateHandle = SavedStateHandle(), generation: UUID? = null): EditorViewModel {
        val owner = SubscriptionsViewModel(object: SubscriptionsSource {
            override fun today() = today
            override fun activeDatasetId() = generation
            override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
        })
        store.put("owner${index++}",owner)
        return EditorViewModel(saved,owner,null).also { store.put("editor${index++}",it) }
    }
    private fun loaded(): Pair<EditorViewModel,Map<String,String>> {
        val row = SubscriptionDomain().create(SubscriptionId.random(),"Сервис",RubPrice(123),
            ScheduleEdit(LocalDate.of(2027,2,28),Frequency(FrequencyUnit.MONTH,1),31),today,
            periodStart = PeriodStart(LocalDate.of(2027,2,1),LocalDate.of(2027,2,28))).toEntity(Instant.EPOCH)
        val vm = model(); dispatcher.scheduler.runCurrent()
        EditorViewModel::class.java.getDeclaredField("expected").apply { isAccessible = true }.set(vm,row)
        @Suppress("UNCHECKED_CAST")
        val fields = EditorViewModel::class.java.getDeclaredMethod("fromRow",SubscriptionEntity::class.java)
            .apply { isAccessible = true }.invoke(vm,row) as Map<String,String>
        @Suppress("UNCHECKED_CAST")
        val state = EditorViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(vm) as MutableStateFlow<EditorState>
        state.value = state.value.copy(fields = fields)
        return vm to fields
    }
    @Test fun returningDateToLoadedValueRetainsScopedPeriodAndOriginalDay() = runTest(dispatcher) {
        val (vm,fields) = loaded()
        vm.change("date","2027-03-02"); vm.change("date",fields.getValue("date"))
        assertEquals("31",vm.state.value.fields["anchorDay"])
        assertEquals(fields["start"],vm.state.value.fields["start"])
        assertEquals(fields["periodDue"],vm.state.value.fields["periodDue"])
        assertTrue(vm.validate(vm.state.value.fields).isEmpty())
    }
    @Test fun returningFrequencyToLoadedValueRetainsScopedPeriod() = runTest(dispatcher) {
        val (vm,fields) = loaded()
        vm.change("unit","WEEK"); vm.change("count","2")
        vm.change("unit","MONTH"); vm.change("count","1")
        assertEquals(fields["start"],vm.state.value.fields["start"])
        assertEquals(fields["periodDue"],vm.state.value.fields["periodDue"])
        assertTrue(vm.validate(vm.state.value.fields).isEmpty())
    }
    @Test fun revertingTemporaryScheduleRestoresEditedPeriodDraftAndExplicitClear() = runTest(dispatcher) {
        val (vm,fields) = loaded()
        vm.change("start","2027-02-02")
        vm.change("date","2027-03-02"); vm.change("date",fields.getValue("date"))
        assertEquals("2027-02-02",vm.state.value.fields["start"])
        vm.change("start","")
        vm.change("count","2"); vm.change("count","1")
        assertEquals("",vm.state.value.fields["start"])
        assertFalse("periodDue" in vm.state.value.fields)
        assertTrue(vm.validate(vm.state.value.fields).isEmpty())
    }
    @Test fun imageOnlyRestoredDraftRejectsChangedGeneration() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf("draftVersion" to 5,"catalogChoice" to "generic-ai",
            "expected" to "null","generation" to UUID.randomUUID().toString()))
        val vm = model(saved,UUID.randomUUID()); runCurrent()
        assertTrue(vm.state.value.conflict); vm.save(); runCurrent()
        assertFalse(vm.state.value.done)
    }
    @Test fun imageRemovalOnlyRestoredDraftRejectsChangedSnapshot() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf("draftVersion" to 5,"imageRemoved" to true,"expected" to "older snapshot"))
        val vm = model(saved); runCurrent()
        assertTrue(vm.state.value.conflict); vm.save(); runCurrent()
        assertFalse(vm.state.value.done)
    }
    @Test fun imageOnlyRestoredDraftWithUnchangedSnapshotRemainsUsable() = runTest(dispatcher) {
        val generation = UUID.randomUUID()
        val vm = model(SavedStateHandle(mapOf("draftVersion" to 5,"catalogChoice" to "generic-ai",
            "expected" to "null","generation" to generation.toString())),generation)
        runCurrent()
        assertFalse(vm.state.value.conflict); assertTrue(vm.state.value.dirty)
        assertEquals("generic-ai",vm.state.value.catalogLogoId)
    }
}
