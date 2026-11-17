package com.glebskorobogatov.subscriptiontracker.ui.finance

import android.content.Context
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict
import com.glebskorobogatov.subscriptiontracker.data.mapping.*
import com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderChanges
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.fixtures.*
import com.glebskorobogatov.subscriptiontracker.ui.archive.*
import com.glebskorobogatov.subscriptiontracker.ui.details.*
import com.glebskorobogatov.subscriptiontracker.ui.editor.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

/** Actual library/Finance/Details/Editor ViewModels → guarded UseCases → isolated Room v5.
 * Correction after the error goes through ViewModels, never a direct repository price repair. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForecastRecoveryIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dataset: UUID
    private lateinit var db: TrackerDatabase
    private lateinit var lease: DatasetSettingsFactory.Lease
    private lateinit var use: SubscriptionUseCases
    private lateinit var date: CurrentDate
    private val store = ViewModelStore()
    private val jobs = mutableListOf<Job>()
    private var number = 0
    @Before fun open() = runBlocking {
        dataset = UUID.randomUUID(); authorizeFreshFixture(context,dataset)
        db = TrackerDatabaseFactory.open(context,dataset); lease = DatasetSettingsFactory.acquire(context,dataset)
        date = CurrentDate(LocalDate.of(2027,1,31))
        use = SubscriptionUseCases(db,lease.repository,date,reminderChanges = ReminderChanges(
            ReminderChanges.gate(context.getDatabasePath("subscriptions-$dataset.db").canonicalPath),
            validate = { check(db.isOpen) },request = {}))
    }
    private suspend fun <T : ViewModel> tracked(create: () -> T): T = withContext(Dispatchers.Main) {
        create().also { store.put("model${number++}",it); jobs += it.viewModelScope.coroutineContext[Job]!! }
    }
    @After fun close() = runBlocking {
        withContext(Dispatchers.Main) { store.clear() }
        withTimeout(30000) { jobs.joinAll() }
        db.close(); lease.close()
        context.deleteDatabase("subscriptions-$dataset.db"); forgetFixtureInitialization(context,"subscriptions-$dataset.db")
        File(context.filesDir,"datastore/settings-$dataset.preferences_pb").delete()
        Unit
    }
    private suspend fun fixture(price: Long = 100, unit: FrequencyUnit = FrequencyUnit.MONTH, count: Int = 1,
        amount: Int = 1): List<SubscriptionEntity> = withContext(Dispatchers.IO) {
        val rows = (1..amount).map {
            SubscriptionDomain().create(SubscriptionId.random(),"Синтетический сервис $it",RubPrice(price),
                ScheduleEdit(date.today.value,Frequency(unit,count)),date.today.value,
                appearance = SubscriptionAppearance(catalogLogoId = "generic-other")).toEntity(Instant.EPOCH)
        }
        db.runInTransaction { rows.forEach { db.subscriptions().insert(it) } }; rows
    }
    private suspend fun owner() = tracked {
        SubscriptionsViewModel(object: SubscriptionsSource {
            override fun activeDatasetId() = dataset
            override fun today() = date.today.value
            override fun observe(refreshes: Flow<Unit>) = refreshes.onStart { emit(Unit) }.flatMapLatest { subscriptionStates(use) }
            override fun details(id: UUID) = use.details(id)
            override fun archive() = archiveRows(use)
            override fun finance() = financeSnapshots(use)
            override fun settings() = use.settings
            override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T = block(use)
        })
    }
    private suspend fun content(owner: SubscriptionsViewModel, predicate: (SubscriptionsUiState.Content) -> Boolean) =
        withTimeout(30000) { owner.state.filterIsInstance<SubscriptionsUiState.Content>().first(predicate) }
    private suspend fun detail(owner: SubscriptionsViewModel,row: SubscriptionEntity): DetailsViewModel {
        val vm = tracked { DetailsViewModel(SavedStateHandle(),owner,row.id) }
        withTimeout(30000) { vm.state.first { it.phase == DetailsPhase.Content } }; return vm
    }
    private suspend fun editor(owner: SubscriptionsViewModel,id: UUID?): EditorViewModel {
        val vm = tracked { EditorViewModel(SavedStateHandle(),owner,id) }
        val state = withTimeout(30000) { vm.state.first { !it.loading } }
        assertFalse(state.loadFailure); assertFalse(state.conflict); return vm
    }
    private suspend fun change(vm: EditorViewModel,key: String,value: String) = withContext(Dispatchers.Main) { vm.change(key,value) }
    private suspend fun save(vm: EditorViewModel) {
        withContext(Dispatchers.Main) { vm.save() }
        val state = withTimeout(30000) { vm.state.first { !it.saving } }
        assertTrue("Editor save must commit",state.done); assertNull(state.error)
    }
    private suspend fun command(vm: DetailsViewModel,action: DetailsAction) {
        withContext(Dispatchers.Main) { vm.open(action); vm.submit() }
        val state = withTimeout(30000) { vm.state.first { it.phase != DetailsPhase.OperationInProgress } }
        assertNull(state.action); assertNull(state.error)
    }
    private suspend fun stored(id: UUID) = withContext(Dispatchers.IO) { db.subscriptions().find(id) }

    @Test fun overflowIsRepairedThroughActualEditorAndBothExistingObserversRecoverAutomatically() = runBlocking {
        val row = fixture(Long.MAX_VALUE).single(); val owner = owner()
        val library = content(owner) { it.active.size == 1 }; assertEquals(row.id,library.active.single().id)
        val finance = tracked { FinanceViewModel(SavedStateHandle(),owner) }
        val failed = withTimeout(30000) { finance.state.first { it.phase == FinancePhase.Error } }
        val failedSnapshot = requireNotNull(failed.snapshot)
        assertNull(failedSnapshot.forecast); assertEquals(row,failedSnapshot.rows.single())
        val details = detail(owner,row); assertEquals(row,details.state.value.data!!.subscription)
        val vm = editor(owner,row.id)
        assertEquals(RubPrice(Long.MAX_VALUE).format(),vm.state.value.fields["amount"])
        change(vm,"amount","1,23"); save(vm)
        content(owner) { it.active.singleOrNull()?.price?.amountMinor == 123L }
        val recovered = withTimeout(30000) { finance.state.first { it.phase == FinancePhase.Content } }
        assertEquals(1476L,recovered.snapshot!!.forecast!!.totalMinor)
        val saved = requireNotNull(stored(row.id)); assertEquals(123L,saved.priceMinor)
        assertEquals(row.toDomain().schedule,saved.toDomain().schedule)
        assertEquals(row.catalogLogoId,saved.catalogLogoId)
    }
    @Test fun cancellationAndConfirmedDeletionThroughDetailsRestoreForecastWithoutOtherRowChanges() = runBlocking {
        val good = fixture().single(); val owner = owner()
        val finance = tracked { FinanceViewModel(SavedStateHandle(),owner) }
        for (action in listOf(DetailsAction.Disable,DetailsAction.Delete)) {
            val bad = fixture(Long.MAX_VALUE).single()
            content(owner) { it.active.any { item -> item.id == bad.id } }
            withTimeout(30000) { finance.state.first { it.phase == FinancePhase.Error && it.snapshot?.rows?.any { item -> item.id == bad.id } == true } }
            val vm = detail(owner,bad)
            if (action == DetailsAction.Delete) {
                withContext(Dispatchers.Main) { vm.open(action); vm.dismiss() }
                assertEquals(bad,stored(bad.id)) // Dismissing confirmation cannot delete.
            }
            command(vm,action)
            content(owner) { it.active.none { item -> item.id == bad.id } }
            val restored = withTimeout(30000) { finance.state.first { it.phase == FinancePhase.Content } }
            assertEquals(200L,restored.snapshot!!.forecast!!.next30Days.totalMinor)
            assertEquals(good,stored(good.id))
            if (action == DetailsAction.Delete) assertNull(stored(bad.id))
            else assertEquals(SubscriptionState.CANCELLED,stored(bad.id)!!.state)
        }
    }
    @Test fun actualEventBudgetDoesNotBlockCreateManualArchiveOrArchiveReader() = runBlocking {
        val rows = fixture(unit = FrequencyUnit.DAY,amount = 300); val owner = owner()
        assertEquals(300,content(owner) { it.active.size == 300 }.active.size)
        val finance = tracked { FinanceViewModel(SavedStateHandle(),owner) }
        withTimeout(30000) { finance.state.first { it.phase == FinancePhase.Error } }
        val archive = tracked { ArchiveViewModel(owner) }
        withTimeout(30000) { archive.state.first { it.phase == ArchivePhase.Empty } }
        val create = editor(owner,null)
        change(create,"name","Новая годовая"); change(create,"amount","1,00"); change(create,"unit","YEAR")
        save(create); assertNotNull(stored(create.savedSubscriptionId))
        for (row in rows.take(2)) command(detail(owner,row),DetailsAction.Archive)
        val recovered = content(owner) { it.active.size == 299 }
        assertEquals(299,recovered.active.size)
        withTimeout(30000) { finance.state.first { it.phase == FinancePhase.Content } }
        withTimeout(30000) { archive.state.first { it.rows.size == 2 } }
        val projection = use.forecasts.first()
        assertEquals(99831,projection.forecast!!.charges.size)
        assertEquals(9983100L,projection.forecast!!.totalMinor)
    }
    @Test fun unrepresentableRecordCanOpenDetailsDismissDeletionAndRepairScheduleInEditor() = runBlocking {
        val row = fixture(unit = FrequencyUnit.YEAR,count = Int.MAX_VALUE).single()
        use.refreshToday(date.today.value.plusDays(1),Instant.EPOCH)
        val owner = owner(); val library = content(owner) { it.active.singleOrNull()?.calendarError == true }
        val finance = tracked { FinanceViewModel(SavedStateHandle(),owner) }
        withTimeout(30000) { finance.state.first { it.phase == FinancePhase.Error } }
        assertTrue(library.active.single().calendarError); assertNull(library.active.single().eventDate)
        val details = detail(owner,row); assertTrue(details.state.value.data!!.calendarError)
        withContext(Dispatchers.Main) { details.open(DetailsAction.Delete); details.dismiss() }
        assertEquals(row,stored(row.id))
        val vm = editor(owner,row.id); assertEquals("",vm.state.value.fields["date"])
        change(vm,"unit","MONTH"); change(vm,"count","1"); change(vm,"date",date.today.value.toString())
        save(vm)
        val recovered = content(owner) { it.active.singleOrNull()?.calendarError == false }
        val projected = withTimeout(30000) { finance.state.first { it.phase == FinancePhase.Content } }
        assertEquals(200L,projected.snapshot!!.forecast!!.next30Days.totalMinor)
        assertFalse(recovered.active.single().calendarError)
        assertEquals(row.id,recovered.active.single().id)
        assertEquals(Frequency(FrequencyUnit.MONTH,1),stored(row.id)!!.toDomain().schedule.frequency)
    }
    @Test fun todayChangeRecoversBudgetErrorWithoutRecreatingTheLibraryObserver() = runBlocking {
        use.refreshToday(LocalDate.of(2027,1,1),Instant.EPOCH)
        val rows = fixture(unit = FrequencyUnit.DAY,amount = 275); val owner = owner()
        assertEquals(275,content(owner) { it.active.size == 275 }.active.size)
        val finance = tracked { FinanceViewModel(SavedStateHandle(),owner) }
        withTimeout(30000) { finance.state.first { it.phase == FinancePhase.Error } }
        use.refreshToday(LocalDate.of(2027,1,31),Instant.EPOCH)
        content(owner) { it.active.all { item -> item.eventDate == LocalDate.of(2027,1,31) } }
        withTimeout(30000) { finance.state.first { it.phase == FinancePhase.Content && it.snapshot?.today == LocalDate.of(2027,1,31) } }
        val projection = use.forecasts.first()
        assertEquals(92125,projection.forecast!!.charges.size)
        assertEquals(rows.toSet(),projection.rows.toSet())
    }
    @Test fun failedForecastDoesNotWeakenOptimisticSnapshotChecks() = runBlocking {
        val row = fixture(Long.MAX_VALUE).single(); val owner = owner()
        content(owner) { it.active.singleOrNull()?.id == row.id }
        val finance = tracked { FinanceViewModel(SavedStateHandle(),owner) }
        withTimeout(30000) { finance.state.first { it.phase == FinancePhase.Error } }
        val vm = editor(owner,row.id)
        // A second writer races the actual form; this is not a repair of the error fixture.
        val concurrent = use.editSubscription(row,SubscriptionEdit("Конкурентное имя",row.accountLabel,RubPrice(Long.MAX_VALUE)),Instant.now())
        change(vm,"amount","1,23")
        withContext(Dispatchers.Main) { vm.save() }
        val result = withTimeout(30000) { vm.state.first { !it.saving } }
        assertTrue(result.conflict); assertFalse(result.done)
        assertEquals(concurrent,stored(row.id)); assertEquals(Long.MAX_VALUE,stored(row.id)!!.priceMinor)
    }
}
