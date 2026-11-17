package com.glebskorobogatov.subscriptiontracker.data.integration

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.toEntity
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import com.glebskorobogatov.subscriptiontracker.ui.editor.EditorViewModel
import com.glebskorobogatov.subscriptiontracker.ui.details.*
import com.glebskorobogatov.subscriptiontracker.ui.archive.*
import com.glebskorobogatov.subscriptiontracker.ui.finance.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Actual projection and ViewModel loading; Android regressions cover actual commands/Room. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForecastModelsAvailabilityTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val today = LocalDate.of(2027,1,2)
    @Before fun open() { Dispatchers.setMain(dispatcher) }
    @After fun close() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private fun row(price: Long = Long.MAX_VALUE, unit: FrequencyUnit = FrequencyUnit.MONTH, count: Int = 1) =
        SubscriptionDomain().create(SubscriptionId(UUID(0,1)),"Синтетический сервис",RubPrice(price),
            ScheduleEdit(today.minusDays(1),Frequency(unit,count)),today.minusDays(1)).toEntity(Instant.EPOCH)
    private fun owner(row: SubscriptionEntity): SubscriptionsViewModel = SubscriptionsViewModel(object: SubscriptionsSource {
        override fun today() = today
        override fun observe(refreshes: Flow<Unit>) = subscriptionStates(flow { emit(forecastSnapshot(listOf(row),today)) })
        override fun details(id: UUID) = flowOf(detailsSnapshot(row,today))
    }).also { store.put("owner",it) }
    @Test fun overflowDoesNotPreventActualEditorReloadOrDetailsLoading() = runTest(dispatcher) {
        val row = row(); val owner = owner(row)
        val loaded = owner.state.first { it !is SubscriptionsUiState.Loading } as SubscriptionsUiState.Content
        assertNull(loaded.forecast)
        val editor = EditorViewModel(SavedStateHandle(),owner,row.id).also { store.put("editor",it) }
        val details = DetailsViewModel(SavedStateHandle(),owner,row.id).also { store.put("details",it) }
        val form = editor.state.first { !it.loading }
        assertFalse(form.loadFailure); assertFalse(form.conflict)
        assertEquals(RubPrice(Long.MAX_VALUE).format(),form.fields["amount"])
        assertEquals(DetailsPhase.Content,details.state.first { it.phase != DetailsPhase.Loading }.phase)
        editor.change("amount","1,23")
        assertTrue(editor.validate(editor.state.value.fields).isEmpty())
    }
    @Test fun unrepresentableDateKeepsIdentityAndAllowsScheduleCorrectionOrExplicitDelete() = runTest(dispatcher) {
        val row = row(price = 123,unit = FrequencyUnit.YEAR,count = Int.MAX_VALUE); val owner = owner(row)
        val library = owner.state.first { it !is SubscriptionsUiState.Loading } as SubscriptionsUiState.Content
        assertTrue(library.active.single().calendarError); assertNull(library.active.single().eventDate)
        val editor = EditorViewModel(SavedStateHandle(),owner,row.id).also { store.put("editor",it) }
        val details = DetailsViewModel(SavedStateHandle(),owner,row.id).also { store.put("details",it) }
        val form = editor.state.first { !it.loading }; assertFalse(form.loadFailure)
        assertEquals("",form.fields["date"]); assertEquals("",form.fields["displayedDue"])
        editor.change("name","Уточнённое имя")
        assertTrue(editor.validate(editor.state.value.fields).isEmpty()) // No accidental reanchor.
        editor.change("unit","MONTH"); editor.change("count","1"); editor.change("date",today.toString())
        assertTrue(editor.validate(editor.state.value.fields).isEmpty())
        details.state.first { it.phase == DetailsPhase.Content }
        details.open(DetailsAction.Delete); assertEquals(DetailsAction.Delete,details.state.value.action)
        details.dismiss(); assertNull(details.state.value.action)
    }
    @Test fun storageBlockedPreventsEditorDetailsArchiveFinanceAndMutationAccess() = runTest(dispatcher) {
        var reads = 0; var writes = 0
        val owner = SubscriptionsViewModel(object: SubscriptionsSource {
            override fun observe(refreshes: Flow<Unit>) = flow<SubscriptionsUiState> { throw StorageUnavailable(StorageFailure.CORRUPT) }
            override fun details(id: UUID) = flow<SubscriptionDetails?> { reads++; emit(null) }
            override fun archive() = flow<List<SubscriptionEntity>> { reads++; emit(emptyList()) }
            override fun finance() = flow<FinanceSnapshot> { reads++; error("Must not read") }
            override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T { writes++; error("Must not mutate") }
        }).also { store.put("owner",it) }
        owner.state.first { it is SubscriptionsUiState.StorageBlocked }
        val editor = EditorViewModel(SavedStateHandle(),owner,UUID(0,1)).also { store.put("editor",it) }
        val details = DetailsViewModel(SavedStateHandle(),owner,UUID(0,1)).also { store.put("details",it) }
        val archive = ArchiveViewModel(owner).also { store.put("archive",it) }
        val finance = FinanceViewModel(SavedStateHandle(),owner).also { store.put("finance",it) }
        assertTrue(editor.state.first { !it.loading }.loadFailure)
        details.state.first { it.phase == DetailsPhase.Error }
        archive.state.first { it.phase == ArchivePhase.Error }
        finance.state.first { it.phase == FinancePhase.Error }
        try { owner.perform { }; fail("Blocked mutation") } catch (_: IllegalStateException) { }
        assertEquals(0,reads); assertEquals(0,writes)
    }
}
