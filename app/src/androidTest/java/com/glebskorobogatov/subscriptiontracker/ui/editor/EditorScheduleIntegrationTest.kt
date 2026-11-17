package com.glebskorobogatov.subscriptiontracker.ui.editor

import android.content.Context
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.*
import com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderChanges
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.fixtures.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

/** Real EditorViewModel → guarded operation → domain → Room, with isolated synthetic data only. */
class EditorScheduleIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dataset: UUID
    private lateinit var generation: UUID
    private lateinit var db: TrackerDatabase
    private lateinit var lease: DatasetSettingsFactory.Lease
    private lateinit var use: SubscriptionUseCases
    private lateinit var date: CurrentDate
    private val domain = SubscriptionDomain()
    private var store = ViewModelStore()
    private val jobs = mutableListOf<Job>()
    private var number = 0
    private var mutations = 0
    private var reconciliations = 0
    @Before fun open() = runBlocking {
        dataset = UUID.randomUUID(); generation = dataset
        authorizeFreshFixture(context,dataset)
        db = TrackerDatabaseFactory.open(context,dataset)
        lease = DatasetSettingsFactory.acquire(context,dataset)
        date = CurrentDate(LocalDate.of(2026,11,8))
        use = SubscriptionUseCases(db,lease.repository,date,
            reminderChanges = ReminderChanges(validate = { mutations++ },request = { reconciliations++ }))
    }
    private suspend fun clearModels() {
        withContext(Dispatchers.Main) { store.clear() }
        jobs.forEach { it.join() }; jobs.clear(); store = ViewModelStore()
    }
    @After fun close() = runBlocking {
        clearModels(); db.close(); lease.close()
        context.deleteDatabase("subscriptions-$dataset.db")
        forgetFixtureInitialization(context,"subscriptions-$dataset.db")
        File(context.filesDir,"datastore/settings-$dataset.preferences_pb").delete()
        Unit
    }
    private suspend fun fixture(unit: FrequencyUnit = FrequencyUnit.WEEK,
        first: LocalDate = LocalDate.of(2026,11,1), count: Int = 1,
        state: SubscriptionState = SubscriptionState.ACTIVE): SubscriptionEntity = withContext(Dispatchers.IO) {
        val original = domain.create(SubscriptionId.random(),"Синтетический сервис",RubPrice(123),
            ScheduleEdit(first,Frequency(unit,count)),first,appearance = SubscriptionAppearance(catalogLogoId = "generic-other"))
        val record = when(state) {
            SubscriptionState.ACTIVE -> original
            SubscriptionState.CANCELLED -> domain.cancel(original,date.today.value)
            SubscriptionState.ARCHIVED -> domain.archive(original,date.today.value,true)
        }
        record.toEntity(Instant.EPOCH).also { db.subscriptions().insert(it) }
    }
    private suspend fun model(row: SubscriptionEntity?, saved: SavedStateHandle = SavedStateHandle()): EditorViewModel {
        val vm = withContext(Dispatchers.Main) {
            val owner = SubscriptionsViewModel(object: SubscriptionsSource {
                override fun activeDatasetId() = generation
                override fun today() = date.today.value
                override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
                override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T = block(use)
            })
            store.put("owner${number++}",owner)
            val editor = EditorViewModel(saved,owner,row?.id)
            store.put("editor${number++}",editor)
            jobs += owner.viewModelScope.coroutineContext[Job]!!
            jobs += editor.viewModelScope.coroutineContext[Job]!!
            editor
        }
        val loaded = withTimeout(5000) { vm.state.first { !it.loading } }
        assertFalse("Synthetic fixture must load",loaded.loadFailure)
        assertFalse(loaded.notFound)
        return vm
    }
    private suspend fun change(vm: EditorViewModel,key: String,value: String) = withContext(Dispatchers.Main) { vm.change(key,value) }
    private suspend fun stored(id: UUID) = withContext(Dispatchers.IO) { requireNotNull(db.subscriptions().find(id)) }
    private suspend fun save(vm: EditorViewModel): SubscriptionEntity {
        withContext(Dispatchers.Main) { vm.save() }
        val result = withTimeout(5000) { vm.state.first { !it.saving } }
        assertTrue("Save failed: field errors=${result.errors.keys}, error=${result.error}",result.done)
        assertTrue(result.errors.isEmpty()); assertNull(result.error)
        return stored(vm.savedSubscriptionId)
    }
    private fun unchangedSeries(before: SubscriptionEntity,after: SubscriptionEntity) {
        assertEquals(before.id,after.id)
        assertEquals(before.toDomain().schedule,after.toDomain().schedule)
        assertEquals(before.createdAt,after.createdAt)
        assertEquals(before.state,after.state)
        assertEquals(before.currentPeriodStartOverride,after.currentPeriodStartOverride)
        assertEquals(before.currentPeriodEnd,after.currentPeriodEnd)
        assertEquals(before.cancelledAccessEndExclusive,after.cancelledAccessEndExclusive)
        assertEquals(before.frozenCancelledPeriodStart,after.frozenCancelledPeriodStart)
        assertEquals(before.archivedAt,after.archivedAt)
    }
    private fun restored(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })

    @Test fun weeklyPriceEditAfterSeveralCyclesSavesWithoutReanchor() = runBlocking {
        date.update(LocalDate.of(2026,11,22))
        val row = fixture(); val vm = model(row)
        assertEquals("2026-11-22",vm.state.value.fields["date"])
        assertEquals("1",vm.state.value.fields["anchorDay"])
        change(vm,"amount","2,34")
        val updated = save(vm)
        assertEquals(234L,updated.priceMinor); unchangedSeries(row,updated)
        assertEquals(1,mutations); assertEquals(1,reconciliations)
    }
    @Test fun dailyNameEditAfterSeveralCyclesSavesWithoutReanchor() = runBlocking {
        val row = fixture(FrequencyUnit.DAY,count = 2); val vm = model(row)
        assertEquals("2026-11-09",vm.state.value.fields["date"])
        change(vm,"name","Новое название")
        val updated = save(vm)
        assertEquals("Новое название",updated.name); unchangedSeries(row,updated)
    }
    @Test fun dailyAndWeeklyPriceEditsAcrossMonthPreserveWholeOriginalAnchor() = runBlocking {
        date.update(LocalDate.of(2026,12,3))
        for ((unit,count) in listOf(FrequencyUnit.DAY to 5,FrequencyUnit.WEEK to 1)) {
            val row = fixture(unit,count = count); val vm = model(row)
            assertEquals("2026-12-06",vm.state.value.fields["date"])
            change(vm,"amount","3,45")
            val updated = save(vm)
            unchangedSeries(row,updated); assertEquals(345L,updated.priceMinor)
        }
    }
    @Test fun appearanceOnlyEditSavesWithoutRebuildingWeeklySchedule() = runBlocking {
        val row = fixture(); val vm = model(row)
        withContext(Dispatchers.Main) { vm.selectCatalog("generic-ai") }
        val updated = save(vm)
        assertEquals("generic-ai",updated.catalogLogoId); assertEquals(row.priceMinor,updated.priceMinor)
        unchangedSeries(row,updated)
    }
    @Test fun explicitDateEditCreatesWholeWeeklySeriesWithoutInheritedDay() = runBlocking {
        val row = fixture(); val vm = model(row)
        change(vm,"date","2026-11-10")
        assertFalse("anchorDay" in vm.state.value.fields)
        val updated = save(vm)
        val schedule = updated.toDomain().schedule
        assertEquals(LocalDate.of(2026,11,10),domain.nextDue(schedule,date.today.value))
        assertEquals(LocalDate.of(2026,11,17),domain.nextDue(schedule,LocalDate.of(2026,11,11)))
        assertEquals(10,updated.anchorDay); assertNotEquals(row.toDomain().schedule,schedule)
    }
    @Test fun frequencyEditCreatesNewDailySeriesWithoutInheritedWeekAnchor() = runBlocking {
        val row = fixture(); val vm = model(row)
        change(vm,"unit","DAY"); change(vm,"count","3")
        assertFalse("anchorDay" in vm.state.value.fields)
        val updated = save(vm)
        assertEquals(Frequency(FrequencyUnit.DAY,3),updated.toDomain().schedule.frequency)
        assertEquals(8,updated.anchorDay)
        assertEquals(LocalDate.of(2026,11,11),domain.nextDue(updated.toDomain().schedule,LocalDate.of(2026,11,9)))
    }
    @Test fun monthly31AtFebruary28IsPreservedByUnrelatedSave() = runBlocking {
        date.update(LocalDate.of(2027,2,15))
        val row = fixture(FrequencyUnit.MONTH,LocalDate.of(2027,1,31)); val vm = model(row)
        assertEquals("2027-02-28",vm.state.value.fields["date"])
        change(vm,"amount","4,56")
        val updated = save(vm)
        unchangedSeries(row,updated)
        assertEquals(LocalDate.of(2027,3,31),domain.nextDue(updated.toDomain().schedule,LocalDate.of(2027,3,1)))
    }
    @Test fun leapYearAnchorIsPreservedByUnrelatedSave() = runBlocking {
        date.update(LocalDate.of(2029,2,10))
        val row = fixture(FrequencyUnit.YEAR,LocalDate.of(2028,2,29)); val vm = model(row)
        assertEquals("2029-02-28",vm.state.value.fields["date"])
        change(vm,"name","Годовой сервис")
        val updated = save(vm)
        unchangedSeries(row,updated)
        assertEquals(LocalDate.of(2032,2,29),domain.nextDue(updated.toDomain().schedule,LocalDate.of(2032,1,1)))
    }
    @Test fun explicitInvalidRefinementIsRejectedBeforeMutation() = runBlocking {
        val row = fixture(); val vm = model(row)
        change(vm,"date","2026-11-10"); change(vm,"anchorDay","1")
        withContext(Dispatchers.Main) { vm.save() }
        assertTrue("anchorDay" in vm.state.value.errors)
        assertFalse(vm.state.value.done); assertEquals(row,stored(row.id))
        assertEquals(0,mutations); assertEquals(0,reconciliations)
    }
    @Test fun cancelledAndArchivedDailyAndWeeklyRecordsSaveOtherFields() = runBlocking {
        for (state in listOf(SubscriptionState.CANCELLED,SubscriptionState.ARCHIVED)) for (unit in listOf(FrequencyUnit.DAY,FrequencyUnit.WEEK)) {
            val row = fixture(unit,state = state); val vm = model(row)
            assertFalse(vm.state.value.scheduleEditable)
            change(vm,"amount","5,67")
            val updated = save(vm)
            assertEquals(567L,updated.priceMinor); unchangedSeries(row,updated)
        }
    }
    @Test fun restoredDraftUsesOriginalDisplayedDueEvenAfterTodayAdvances() = runBlocking {
        val row = fixture(); val handle = SavedStateHandle(); val vm = model(row,handle)
        change(vm,"amount","6,78")
        val snapshot = restored(handle)
        clearModels(); date.update(LocalDate.of(2026,12,3))
        val next = model(row,snapshot)
        assertEquals("2026-11-08",next.state.value.fields["date"])
        assertFalse(next.state.value.conflict)
        val updated = save(next)
        assertEquals(678L,updated.priceMinor); unchangedSeries(row,updated)
    }
    @Test fun staleUnchangedDateInOpenDraftDoesNotBlockPriceEdit() = runBlocking {
        val row = fixture(); val vm = model(row)
        date.update(LocalDate.of(2026,12,3))
        change(vm,"amount","7,89")
        val updated = save(vm)
        assertEquals(789L,updated.priceMinor); unchangedSeries(row,updated)
    }
    @Test fun newSubscriptionStillRequiresValidNewSeriesAndOnlyOneSave() = runBlocking {
        val vm = model(null)
        change(vm,"name","Новая подписка"); change(vm,"amount","1,23"); change(vm,"unit","WEEK")
        withContext(Dispatchers.Main) { vm.save(); vm.save() }
        val done = withTimeout(5000) { vm.state.first { !it.saving } }
        assertTrue(done.done)
        val row = stored(vm.savedSubscriptionId)
        assertEquals(8,row.anchorDay); assertEquals(123L,row.priceMinor)
        assertEquals(1,withContext(Dispatchers.IO) { db.subscriptions().all().size })
        assertEquals(1,mutations)
    }
    @Test fun newSeriesWithPastDateIsRejectedWhileUnchangedDraftMayBeOld() = runBlocking {
        val row = fixture(); val vm = model(row)
        change(vm,"date","2026-11-07")
        withContext(Dispatchers.Main) { vm.save() }
        assertTrue("date" in vm.state.value.errors); assertEquals(row,stored(row.id))
        assertEquals(0,mutations)
    }
    @Test fun explicitMonthlyAndAnnualRefinementsRemainAvailable() = runBlocking {
        date.update(LocalDate.of(2027,2,15))
        val row = fixture(FrequencyUnit.MONTH,LocalDate.of(2027,1,28)); val vm = model(row)
        change(vm,"anchorDay","31")
        val monthly = save(vm)
        assertEquals(31,monthly.anchorDay)
        assertEquals(LocalDate.of(2027,3,31),domain.nextDue(monthly.toDomain().schedule,LocalDate.of(2027,3,1)))
        date.update(LocalDate.of(2029,2,10))
        val annualRow = fixture(FrequencyUnit.YEAR,LocalDate.of(2028,2,28)); val annualVm = model(annualRow)
        change(annualVm,"anchorDay","29")
        val annual = save(annualVm)
        assertEquals(29,annual.anchorDay)
        assertEquals(LocalDate.of(2032,2,29),domain.nextDue(annual.toDomain().schedule,LocalDate.of(2032,1,1)))
    }
    @Test fun concurrentRowChangeStillRejectsOptimisticSave() = runBlocking {
        val row = fixture(); val vm = model(row)
        val concurrent = use.editSubscription(row,SubscriptionEdit("Конкурентное имя",row.accountLabel,RubPrice(999)),Instant.now())
        change(vm,"amount","2,34")
        withContext(Dispatchers.Main) { vm.save() }
        val result = withTimeout(5000) { vm.state.first { !it.saving } }
        assertTrue(result.conflict); assertFalse(result.done)
        assertEquals(concurrent,stored(row.id)); assertEquals(1,reconciliations)
    }
    @Test fun changedGenerationStillRejectsSaveBeforeMutation() = runBlocking {
        val row = fixture(); val vm = model(row)
        change(vm,"amount","2,34"); generation = UUID.randomUUID()
        withContext(Dispatchers.Main) { vm.save() }
        val result = withTimeout(5000) { vm.state.first { !it.saving } }
        assertTrue(result.conflict); assertEquals(row,stored(row.id)); assertEquals(0,mutations)
    }
    @Test fun restoredSnapshotStillDetectsConcurrentEdit() = runBlocking {
        val row = fixture(); val handle = SavedStateHandle(); val vm = model(row,handle)
        change(vm,"amount","2,34")
        val saved = restored(handle); clearModels()
        val concurrent = use.editSubscription(row,SubscriptionEdit("Другая версия",row.accountLabel,RubPrice(999)),Instant.now())
        val restored = model(row,saved)
        assertTrue(restored.state.value.conflict)
        withContext(Dispatchers.Main) { restored.save() }
        assertFalse(restored.state.value.done); assertEquals(concurrent,stored(row.id))
        assertEquals(1,reconciliations)
    }

    @Test fun revertedDateAndFrequencyPreserveScopedPeriodThroughRecreationAndSave() = runBlocking {
        date.update(LocalDate.of(2027,2,15))
        for (field in listOf("date","unit","count")) {
            val base = fixture(FrequencyUnit.MONTH,LocalDate.of(2027,1,31))
            val row = base.copy(currentPeriodStartOverride = LocalDate.of(2027,2,1),currentPeriodEnd = LocalDate.of(2027,2,28))
            withContext(Dispatchers.IO) { db.subscriptions().update(row) }
            val handle = SavedStateHandle(); val vm = model(row,handle)
            val original = vm.state.value.fields.getValue(field)
            change(vm,field,when(field) { "date" -> "2027-03-02"; "unit" -> "WEEK"; else -> "2" })
            change(vm,field,original)
            assertTrue(vm.validate(vm.state.value.fields).isEmpty())
            val snapshot = restored(handle); clearModels()
            val next = model(row,snapshot)
            change(next,"amount","2,34")
            val updated = save(next)
            unchangedSeries(row,updated); assertEquals(234L,updated.priceMinor)
        }
    }
    @Test fun explicitPeriodClearAfterRevertingScheduleStillClearsOnlyOverride() = runBlocking {
        date.update(LocalDate.of(2027,2,15))
        val base = fixture(FrequencyUnit.MONTH,LocalDate.of(2027,1,31))
        val row = base.copy(currentPeriodStartOverride = LocalDate.of(2027,2,1),currentPeriodEnd = LocalDate.of(2027,2,28))
        withContext(Dispatchers.IO) { db.subscriptions().update(row) }
        val vm = model(row)
        change(vm,"date","2027-03-02"); change(vm,"date","2027-02-28"); change(vm,"start","")
        val updated = save(vm)
        assertNull(updated.currentPeriodStartOverride); assertNull(updated.currentPeriodEnd)
        assertEquals(row.toDomain().schedule,updated.toDomain().schedule)
    }
    @Test fun imageOnlyDraftRecreationPreservesFieldsAndOriginalSeriesAsTodayAdvances() = runBlocking {
        val row = fixture(); val handle = SavedStateHandle(); val vm = model(row,handle)
        withContext(Dispatchers.Main) { vm.selectCatalog("generic-ai") }
        val snapshot = restored(handle); clearModels(); date.update(LocalDate.of(2026,12,3))
        val next = model(row,snapshot)
        assertEquals("2026-11-08",next.state.value.fields["date"])
        val updated = save(next)
        assertEquals("generic-ai",updated.catalogLogoId); unchangedSeries(row,updated)
    }
    @Test fun imageOnlyRestoredDraftRejectsConcurrentRowBeforeMutation() = runBlocking {
        val row = fixture(); val handle = SavedStateHandle(); val vm = model(row,handle)
        withContext(Dispatchers.Main) { vm.selectCatalog("generic-ai") }
        // Accepted v5 builds could save image-only drafts without the fields map.
        handle.remove<HashMap<String,String>>("fields")
        val snapshot = restored(handle); clearModels()
        val concurrent = use.editSubscription(row,SubscriptionEdit("Конкурентная версия",row.accountLabel,RubPrice(999)),Instant.now())
        val next = model(row,snapshot)
        assertTrue(next.state.value.conflict)
        withContext(Dispatchers.Main) { next.save() }
        assertFalse(next.state.value.done); assertEquals(concurrent,stored(row.id))
        assertEquals(1,mutations); assertEquals(1,reconciliations)
    }
    @Test fun imageOnlyRestoredDraftRejectsChangedGenerationBeforeMutation() = runBlocking {
        val row = fixture(); val handle = SavedStateHandle(); val vm = model(row,handle)
        withContext(Dispatchers.Main) { vm.removeImage() }
        handle.remove<HashMap<String,String>>("fields")
        val snapshot = restored(handle); clearModels(); generation = UUID.randomUUID()
        val next = model(row,snapshot)
        assertTrue(next.state.value.conflict)
        withContext(Dispatchers.Main) { next.save() }
        assertFalse(next.state.value.done); assertEquals(row,stored(row.id))
        assertEquals(0,mutations); assertEquals(0,reconciliations)
    }

    @Test fun temporaryScheduleDraftRetainsEditedStartAcrossRecreationBeforeRevert() = runBlocking {
        date.update(LocalDate.of(2027,2,15))
        val row = fixture(FrequencyUnit.MONTH,LocalDate.of(2027,1,31))
        val handle = SavedStateHandle(); val vm = model(row,handle)
        change(vm,"start","2027-02-02"); change(vm,"date","2027-03-02")
        val snapshot = restored(handle); clearModels()
        val next = model(row,snapshot)
        change(next,"date","2027-02-28")
        val updated = save(next)
        assertEquals(row.toDomain().schedule,updated.toDomain().schedule)
        assertEquals(LocalDate.of(2027,2,2),updated.currentPeriodStartOverride)
        assertEquals(LocalDate.of(2027,2,28),updated.currentPeriodEnd)
    }
    @Test fun temporaryDailyAndWeeklyEditsAcrossMonthReturnToOriginalSeries() = runBlocking {
        date.update(LocalDate.of(2026,12,3))
        for ((unit,count) in listOf(FrequencyUnit.DAY to 5,FrequencyUnit.WEEK to 1)) {
            val row = fixture(unit,count = count); val vm = model(row)
            val originalDate = vm.state.value.fields.getValue("date")
            change(vm,"date","2026-12-10"); change(vm,"unit","MONTH")
            change(vm,"date",originalDate); change(vm,"unit",unit.name)
            change(vm,"name","Изменено только имя")
            val updated = save(vm)
            unchangedSeries(row,updated); assertEquals("Изменено только имя",updated.name)
        }
    }
}
