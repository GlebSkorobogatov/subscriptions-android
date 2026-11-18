package com.glebskorobogatov.subscriptiontracker.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.mapping.toEntity
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Presentation and one-step frequency updates. Calendar arithmetic stays in the domain tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorFrequencyTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val today = LocalDate.of(2027, 2, 15)
    private var index = 0
    @Before fun open() { Dispatchers.setMain(dispatcher) }
    @After fun close() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }

    @Test fun fivePresetsAndSixMonthsStayCustom() {
        assertEquals(EditorPeriodPreset.WEEK, editorPeriodPreset("WEEK", "1"))
        assertEquals(EditorPeriodPreset.MONTH, editorPeriodPreset("MONTH", "1"))
        assertEquals(EditorPeriodPreset.QUARTER, editorPeriodPreset("MONTH", "3"))
        assertEquals(EditorPeriodPreset.YEAR, editorPeriodPreset("YEAR", "1"))
        assertEquals(EditorPeriodPreset.CUSTOM, editorPeriodPreset("MONTH", "6"))
        assertEquals(EditorPeriodPreset.CUSTOM, editorPeriodPreset("DAY", "14"))
        assertEquals(EditorPeriodPreset.CUSTOM, editorPeriodPreset("YEAR", "2"))
        assertEquals(EditorPeriodPreset.CUSTOM, editorPeriodPreset(null, null))
    }

    @Test fun hiddenAnchorOrStartErrorOpensExtraAndAmountErrorDoesNot() {
        assertFalse(editorExtraVisible(false, emptyMap<String, Int>()))
        assertTrue(editorExtraVisible(true, emptyMap<String, Int>()))
        assertTrue(editorExtraVisible(false, mapOf("anchorDay" to R.string.editor_invalid)))
        assertTrue(editorExtraVisible(false, mapOf("start" to R.string.editor_invalid)))
        assertFalse(editorExtraVisible(false, mapOf("amount" to R.string.editor_invalid)))
    }

    @Test fun changeFrequencyPublishesQuarterWithoutIntermediateMonth() = runTest(dispatcher) {
        val (vm, _) = loaded()
        val seen = mutableListOf<Pair<String?, String?>>()
        val job = launch { vm.state.collect { seen += it.fields["unit"] to it.fields["count"] } }
        runCurrent()
        vm.changeFrequency("MONTH", "3")
        runCurrent()
        job.cancel()
        assertEquals(listOf("MONTH" to "1", "MONTH" to "3"), seen)
        assertFalse("anchorDay" in vm.state.value.fields)
    }

    @Test fun returningToLoadedFrequencyRestoresRefinementsInOneStep() = runTest(dispatcher) {
        val (vm, fields) = loaded()
        vm.changeFrequency("WEEK", "2")
        assertFalse("anchorDay" in vm.state.value.fields)
        vm.changeFrequency("MONTH", "1")
        assertEquals("31", vm.state.value.fields["anchorDay"])
        assertEquals(fields["start"], vm.state.value.fields["start"])
        assertEquals(fields["periodDue"], vm.state.value.fields["periodDue"])
        assertTrue(vm.validate(vm.state.value.fields).isEmpty())
    }

    @Test fun customSixMonthsRoundTripKeepsTheOriginalSeries() = runTest(dispatcher) {
        val (vm, fields) = loaded()
        vm.changeFrequency("MONTH", "6")
        assertEquals(EditorPeriodPreset.CUSTOM, editorPeriodPreset(vm.state.value.fields["unit"], vm.state.value.fields["count"]))
        assertFalse("start" in vm.state.value.fields)
        vm.changeFrequency("MONTH", "1")
        assertEquals(fields["start"], vm.state.value.fields["start"])
        assertEquals("31", vm.state.value.fields["anchorDay"])
    }

    @Test fun customCountRejectsNonPositiveAndUnknownUnits() = runTest(dispatcher) {
        val (vm, fields) = loaded()
        vm.changeFrequency("MONTH", "0")
        vm.changeFrequency("MONTH", "-4")
        vm.changeFrequency("MONTH", "1.5")
        vm.changeFrequency("HOUR", "1")
        assertEquals(fields["unit"], vm.state.value.fields["unit"])
        assertEquals(fields["count"], vm.state.value.fields["count"])
        assertEquals("31", vm.state.value.fields["anchorDay"])
        vm.changeFrequency("DAY", "14")
        assertEquals("DAY", vm.state.value.fields["unit"])
        assertEquals("14", vm.state.value.fields["count"])
    }

    @Test fun nameAndPriceDoNotMoveTheAnchor() = runTest(dispatcher) {
        val (vm, fields) = loaded()
        vm.change("name", "Другое имя")
        vm.change("amount", "299,50")
        assertEquals("31", vm.state.value.fields["anchorDay"])
        assertEquals(fields["date"], vm.state.value.fields["date"])
        assertEquals(fields["unit"], vm.state.value.fields["unit"])
        assertEquals(fields["count"], vm.state.value.fields["count"])
        assertTrue(vm.validate(vm.state.value.fields).isEmpty())
    }

    @Test fun dateChangeClearsRefinementsUntilTheLoadedDateReturns() = runTest(dispatcher) {
        val (vm, fields) = loaded()
        vm.change("date", "2027-03-02")
        assertEquals("2027-03-02", vm.state.value.fields["date"])
        assertFalse("anchorDay" in vm.state.value.fields)
        assertTrue(vm.validate(vm.state.value.fields).isEmpty())
        vm.change("date", fields.getValue("date"))
        assertEquals("31", vm.state.value.fields["anchorDay"])
        assertEquals(fields["start"], vm.state.value.fields["start"])
    }

    @Test fun amountValidationStaysInKopecks() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        fun errors(amount: String) = vm.validate(mapOf(
            "name" to "Сервис", "amount" to amount, "unit" to "MONTH", "count" to "1", "date" to "2027-03-01"))
        assertTrue(errors("299,50").isEmpty())
        assertTrue(errors("299").isEmpty())
        assertTrue(errors("92233720368547758,07").isEmpty())
        for (amount in listOf("0", "0,00", "-1", "нет", "299,505", "1.001", "92233720368547758,08", "9223372036854775807"))
            assertEquals(amount, R.string.editor_invalid, errors(amount)["amount"])
    }

    @Test fun saveDoesNotCommitWhileBlockedAndKeepsTheDraft() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = model(saved)
        runCurrent()
        vm.change("name", "Черновик")
        vm.change("amount", "10")
        val flow = stateOf(vm)
        val draft = flow.value
        for (blocked in listOf(
            draft.copy(saving = true),
            draft.copy(importingImage = true),
            draft.copy(loading = true),
            draft.copy(conflict = true),
            draft.copy(loadFailure = true),
            draft.copy(notFound = true),
            draft.copy(done = true),
        )) {
            flow.value = blocked
            vm.save()
            runCurrent()
            assertEquals(blocked.done, vm.state.value.done)
            assertEquals(blocked.saving, vm.state.value.saving)
            assertEquals("Черновик", saved.get<HashMap<String, String>>("fields")?.get("name"))
        }
        flow.value = draft.copy(saving = false, importingImage = false, loading = false, conflict = false,
            loadFailure = false, notFound = false, done = false)
        vm.save()
        runCurrent()
        assertFalse(vm.state.value.done)
        assertEquals(R.string.editor_save_error, vm.state.value.error)
        assertEquals("Черновик", saved.get<HashMap<String, String>>("fields")?.get("name"))
    }

    @Test fun frequencyChangeKeepsDraftVersionAndHandle() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = model(saved)
        runCurrent()
        vm.changeFrequency("YEAR", "1")
        assertEquals(5, saved.get<Int>("draftVersion"))
        assertEquals("YEAR", saved.get<HashMap<String, String>>("fields")?.get("unit"))
        assertEquals("1", saved.get<HashMap<String, String>>("fields")?.get("count"))
        assertNotNull(saved.get<String>("newId"))
    }

    private fun model(saved: SavedStateHandle = SavedStateHandle()): EditorViewModel {
        val owner = SubscriptionsViewModel(object : SubscriptionsSource {
            override fun today() = today
            override fun activeDatasetId(): UUID? = null
            override fun observe(refreshes: kotlinx.coroutines.flow.Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
        })
        store.put("owner${index++}", owner)
        return EditorViewModel(saved, owner, null).also { store.put("editor${index++}", it) }
    }

    private fun loaded(): Pair<EditorViewModel, Map<String, String>> {
        val row = SubscriptionDomain().create(SubscriptionId.random(), "Сервис", RubPrice(123),
            ScheduleEdit(LocalDate.of(2027, 2, 28), Frequency(FrequencyUnit.MONTH, 1), 31), today,
            periodStart = PeriodStart(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28))).toEntity(Instant.EPOCH)
        val vm = model()
        dispatcher.scheduler.runCurrent()
        EditorViewModel::class.java.getDeclaredField("expected").apply { isAccessible = true }.set(vm, row)
        @Suppress("UNCHECKED_CAST")
        val fields = EditorViewModel::class.java.getDeclaredMethod("fromRow", SubscriptionEntity::class.java)
            .apply { isAccessible = true }.invoke(vm, row) as Map<String, String>
        stateOf(vm).value = vm.state.value.copy(fields = fields, loading = false)
        return vm to fields
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateOf(vm: EditorViewModel) = EditorViewModel::class.java.getDeclaredField("mutable")
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<EditorState>
}
