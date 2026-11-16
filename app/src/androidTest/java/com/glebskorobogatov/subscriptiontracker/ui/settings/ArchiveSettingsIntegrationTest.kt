package com.glebskorobogatov.subscriptiontracker.ui.settings

import android.content.Context
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.glebskorobogatov.subscriptiontracker.app.SubscriptionsComposition
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.archive.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.io.File
import java.io.IOException
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class ArchiveSettingsIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dataset = UUID.randomUUID(); private val store = ViewModelStore(); private val jobs = mutableListOf<Job>()
    private lateinit var db: TrackerDatabase; private lateinit var lease: DatasetSettingsFactory.Lease; private lateinit var use: SubscriptionUseCases
    private val day = LocalDate.of(2027,1,31); private val now = Instant.parse("2027-01-31T12:00:00Z"); private val rub = CurrencyCode.of("RUB")
    @Before fun open() = runBlocking { com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset); reopen() }
    private suspend fun reopen() { db = TrackerDatabaseFactory.open(context,dataset); lease = DatasetSettingsFactory.acquire(context,dataset); use = SubscriptionUseCases(db,lease.repository,CurrentDate(day)) }
    private suspend fun clear() { withContext(Dispatchers.Main) { store.clear() }; jobs.forEach { it.join() }; jobs.clear() }
    @After fun close() = runBlocking { clear(); db.close(); lease.close(); context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }; File(context.filesDir,"datastore/settings-$dataset.preferences_pb").delete(); Unit }
    private suspend fun create(label: String = "Личный",overrides: Boolean = false): SubscriptionEntity {
        val id = UUID.randomUUID()
        val rules = if(overrides) listOf(ReminderOverrideEntity(id,ReminderEventType.PAYMENT,ReminderMode.CUSTOM,setOf(0)),
            ReminderOverrideEntity(id,ReminderEventType.CANCELLATION,ReminderMode.INHERIT_GLOBAL,emptySet())) else emptyList()
        return use.createSubscription(SubscriptionDraft(id,"Одинаковое имя",label,Money(49900,rub),RenewalMode.AUTO,
            RenewalSchedule(RenewalAnchor.from(day.plusDays(2)),Frequency(FrequencyUnit.MONTH,1)),reminders = rules),now)
    }
    private fun source() = object: SubscriptionsSource {
        override fun today() = day
        override fun settings() = use.settings
        override fun archive() = archiveRows(use)
        override fun details(id: UUID) = use.details(id)
        override fun observe(refreshes: Flow<Unit>) = subscriptionStates(use)
        override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T = block(use)
    }
    private suspend fun owner(source: SubscriptionsSource): SubscriptionsViewModel = withContext(Dispatchers.Main) {
        SubscriptionsViewModel(source).also { store.put("owner",it); jobs += it.viewModelScope.coroutineContext[Job]!! }
    }
    private suspend fun settings(owner: SubscriptionsViewModel,saved: SavedStateHandle = SavedStateHandle()) = withContext(Dispatchers.Main) {
        SettingsViewModel(saved,owner).also { store.put("settings",it) }
    }
    private suspend fun loaded(vm: SettingsViewModel) = withTimeout(5000) { vm.state.first { it.phase == SettingsPhase.Content && !it.pending } }
    private suspend fun details(id: UUID) = withTimeout(5000) { use.details(id).first() }!!
    @Test fun archiveProjectionReactivationPreservesHistoryAndUuidAndRecomputesForecast() = runBlocking {
        val a = create(); val b = create("Семейный")
        val p = use.addHistoricalPayment(OperationId.random(),SubscriptionId(a.id),day,Money(99900,rub),now)
        val r = use.addRefund(RefundEntity(UUID.randomUUID(),p.id,day,30000,rub))
        val archived = use.changeLifecycle(a,LifecycleCommand.Archive,now); use.changeLifecycle(b,LifecycleCommand.Archive,now)
        val owner = owner(source()); val vm = withContext(Dispatchers.Main) { ArchiveViewModel(owner).also { store.put("archive",it) } }
        withTimeout(5000) { vm.state.first { it.rows.size == 2 } }
        assertEquals(setOf(a.id,b.id),vm.state.value.rows.map { it.id }.toSet())
        val command = LifecycleCommand.Reactivate(day.plusDays(5),Frequency(FrequencyUnit.MONTH,3),RenewalMode.MANUAL,Money(59900,CurrencyCode.of("USD")))
        val restored = use.changeLifecycle(archived,command,now.plusSeconds(1))
        withTimeout(5000) { vm.state.first { it.rows.map { row -> row.id } == listOf(b.id) } }
        // Classification and forecast are independent reactive streams: await both updates.
        val active = withTimeout(5000) { owner.state.first {
            it is SubscriptionsUiState.Content && it.active.singleOrNull()?.id == a.id && it.forecast.isNotEmpty()
        } } as SubscriptionsUiState.Content
        assertEquals("Reactivated subscription must have one forecast currency",1,active.forecast.size)
        assertEquals(a.id,restored.id); assertEquals("USD",active.forecast.single().currency.value); assertEquals(59900L,active.forecast.single().amountMinor)
        val preserved = details(a.id).history
        assertEquals("Reactivation must preserve the historical payment",1,preserved.size)
        val history = preserved.single(); assertEquals(p,history.payment); assertEquals(listOf(r),history.refunds)
        try { use.changeLifecycle(archived,command,now); fail() } catch(_: LifecycleConflict) { }
        assertEquals(restored,details(a.id).subscription)
    }
    @Test fun settingsCommandsPersistAcrossReopenWithoutOverwritingCustomOrOtherFields() = runBlocking {
        val row = create(overrides = true); val before = details(row.id)
        val owner = owner(source()); val vm = settings(owner); loaded(vm)
        suspend fun change(block: () -> Unit) { withContext(Dispatchers.Main) { block() }; loaded(vm) }
        change { vm.setTheme(Theme.DARK) }; change { vm.confirmTime(18,45) }
        change { vm.setOffset(true,7,true) }; change { vm.setOffset(false,7,false) }; change { vm.setOffset(false,1,true) }
        val actual = use.settings.first(); assertEquals(Theme.DARK,actual.theme); assertEquals(LocalTime.of(18,45),actual.reminderLocalTime)
        assertEquals(setOf(7,3,1),actual.paymentOffsetsDefault); assertEquals(setOf(3,1),actual.cancellationOffsetsDefault)
        val after = details(row.id); assertEquals(before,after)
        assertEquals(setOf(0),effectiveOffsets(actual,ReminderEventType.PAYMENT,after.reminders.first { it.eventType == ReminderEventType.PAYMENT }))
        assertEquals(setOf(3,1),effectiveOffsets(actual,ReminderEventType.CANCELLATION,after.reminders.first { it.eventType == ReminderEventType.CANCELLATION }))
        assertEquals(setOf(7,3,1),effectiveOffsets(actual,ReminderEventType.PAYMENT,null))
        val disabled = ReminderOverrideEntity(row.id,ReminderEventType.CANCELLATION,ReminderMode.CUSTOM,emptySet())
        assertTrue(effectiveOffsets(actual,ReminderEventType.CANCELLATION,disabled).isEmpty())
        clear(); db.close(); lease.close(); reopen()
        assertEquals(actual,use.settings.first()); assertEquals(before,details(row.id))
    }
    @Test fun failedReadAndLostWriteResponsePreservePreferencesAndRestoreWithoutAutomaticWrite() = runBlocking {
        use.updateSettings { it.copy(theme = Theme.DARK,reminderLocalTime = LocalTime.of(18,45)) }
        val baseline = use.settings.first(); var badRead = true; var lose = true; var writes = 0
        val src = object: SubscriptionsSource {
            override fun observe(refreshes: Flow<Unit>) = subscriptionStates(use)
            override fun settings() = flow { if(badRead) throw IOException("read"); emitAll(use.settings) }
            override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T {
                writes++; val result = block(use); if(lose) { lose = false; throw IOException("Committed, response lost") }; return result
            }
        }
        val owner = owner(src); val saved = SavedStateHandle(); val vm = settings(owner,saved)
        withTimeout(5000) { vm.state.first { it.phase == SettingsPhase.Error } }; assertEquals(baseline,use.settings.first())
        badRead = false; withContext(Dispatchers.Main) { vm.retryRead() }; loaded(vm); assertEquals(baseline,vm.state.value.data)
        withContext(Dispatchers.Main) { vm.setOffset(true,7,true) }
        withTimeout(5000) { vm.state.first { it.phase == SettingsPhase.Content && it.pending && it.error != null } }
        assertEquals(1,writes)
        val next = settings(owner,SavedStateHandle(mapOf("intent" to saved.get<HashMap<String,String>>("intent"))))
        withTimeout(5000) { next.state.first { it.phase == SettingsPhase.Content } }; assertEquals(1,writes)
        withContext(Dispatchers.Main) { next.retryWrite() }; loaded(next); assertEquals(2,writes)
        assertEquals(baseline.copy(paymentOffsetsDefault = setOf(7,3,1)),use.settings.first())
    }
    @Test fun productionSessionRetryKeepsSameDatasetAndReconnectsArchiveAndSettingsBeforeFinalRelease() = runBlocking {
        db.close(); lease.close()
        val composition = SubscriptionsComposition(context) { dataset }; var failNext = false
        val src = object: SubscriptionsSource {
            override fun settings() = composition.settings(); override fun archive() = composition.archive()
            override fun details(id: UUID) = composition.details(id)
            override fun today() = composition.today()
            override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T = composition.perform(block)
            override fun observe(refreshes: Flow<Unit>) = composition.observe(refreshes).onEach { if(failNext) { failNext = false; throw IOException("Injected read failure") } }
        }
        val owner = owner(src); val preferences = settings(owner); loaded(preferences)
        val archived = owner.perform { use ->
            val s = use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Сессия",null,Money(49900,rub),RenewalMode.AUTO,
                RenewalSchedule(RenewalAnchor.from(LocalDate.now()),Frequency(FrequencyUnit.MONTH,1))),Instant.now())
            use.changeLifecycle(s,LifecycleCommand.Archive,Instant.now())
        }
        val archive = withContext(Dispatchers.Main) { ArchiveViewModel(owner).also { store.put("archive",it) } }
        withTimeout(5000) { archive.state.first { it.rows.singleOrNull()?.id == archived.id } }
        withContext(Dispatchers.Main) { preferences.setTheme(Theme.DARK) }; loaded(preferences)
        failNext = true; owner.refreshToday()
        withTimeout(5000) { owner.state.first { it is SubscriptionsUiState.Error } }
        withContext(Dispatchers.Main) { owner.retry() }
        withTimeout(5000) { owner.state.first { it is SubscriptionsUiState.Empty || it is SubscriptionsUiState.Content } }; loaded(preferences)
        withTimeout(5000) { archive.state.first { it.phase == ArchivePhase.Content && it.rows.singleOrNull()?.id == archived.id } }
        assertEquals(Theme.DARK,preferences.state.value.data!!.theme)
        assertEquals(archived,owner.perform { it.details(archived.id).first() }!!.subscription)
        clear(); reopen(); assertEquals(Theme.DARK,use.settings.first().theme); assertEquals(archived,details(archived.id).subscription)
    }
}
