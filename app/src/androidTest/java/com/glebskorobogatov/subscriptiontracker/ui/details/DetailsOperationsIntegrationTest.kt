package com.glebskorobogatov.subscriptiontracker.ui.details

import android.content.Context
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict
import com.glebskorobogatov.subscriptiontracker.data.mapping.SubscriptionSnapshot
import com.glebskorobogatov.subscriptiontracker.fixtures.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.io.File
import java.io.IOException
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class DetailsOperationsIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dataset = UUID.randomUUID()
    private lateinit var db: TrackerDatabase
    private lateinit var lease: DatasetSettingsFactory.Lease
    private lateinit var use: SubscriptionUseCases
    private val day = LocalDate.of(2027,1,31)
    private val now = Instant.parse("2027-01-31T12:00:00Z")
    private val rub = CurrencyCode.of("RUB")
    private var store = ViewModelStore()
    private val owners = mutableListOf<Job>()
    @Before fun open() = runBlocking {
        authorizeFreshFixture(context, dataset)
        reopen()
    }
    private suspend fun reopen() {
        db = TrackerDatabaseFactory.open(context,dataset)
        lease = DatasetSettingsFactory.acquire(context,dataset)
        use = SubscriptionUseCases(db,lease.repository,CurrentDate(day))
    }
    private suspend fun clearModels() {
        withContext(Dispatchers.Main) { store.clear() }
        owners.forEach { it.join() }; owners.clear(); store = ViewModelStore()
    }
    @After fun close() = runBlocking {
        clearModels(); db.close(); lease.close()
        context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }
        File(context.filesDir,"datastore/settings-$dataset.preferences_pb").delete(); Unit
    }
    private suspend fun create(label: String = "Личный") = use.createSubscription(SubscriptionDraft(id = UUID.randomUUID(), name = "Одинаковое имя", accountLabel = label, price = RubPrice(49900), schedule = ScheduleEdit(day, Frequency(FrequencyUnit.MONTH,1))),now)
    private suspend fun details(id: UUID) = withTimeout(5000) { use.details(id).first() }!!
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch(_: IllegalArgumentException) { } catch(_: IllegalStateException) { }
    }
    @Test fun liveLifecycleDetailsPreserveOtherSubscriptionAndLegacyRows() = runBlocking {
        val s = create(); val other = create("Семейный")
        val events = Channel<SubscriptionDetails?>(Channel.UNLIMITED)
        val reader = launch { use.details(s.id).collect { events.send(it) } }
        try {
            assertEquals(s,withTimeout(5000) { events.receive() }!!.subscription)
            val archived = use.changeLifecycle(s,LifecycleCommand.Archive,now)
            withTimeout(5000) { events.receiveAsFlow().first { it?.subscription == archived } }
            val restored = use.changeLifecycle(archived,LifecycleCommand.Reactivate(RubPrice(s.priceMinor), ScheduleEdit(day.plusDays(5),Frequency(FrequencyUnit.MONTH,1))),now.plusSeconds(1))
            withTimeout(5000) { events.receiveAsFlow().first { it?.subscription == restored } }
            assertEquals(other,details(other.id).subscription)
            withContext(Dispatchers.IO) {
            }
        } finally { reader.cancelAndJoin(); events.close() }
    }
    @Test fun completeSavedSubscriptionSnapshotRejectsConcurrentEditAfterReopen() = runBlocking {
        val s = create()
        val saved = SubscriptionSnapshot.snapshot(s)
        val edited = use.editSubscription(s,SubscriptionEdit(name = s.name, accountLabel = s.accountLabel, price = RubPrice(s.priceMinor+1)),now.plusSeconds(1))
        db.close(); lease.close(); reopen()
        try { use.deleteSubscription(SubscriptionSnapshot.row(saved),true); fail() } catch (_: LifecycleConflict) { }
        assertEquals(edited,details(s.id).subscription)
    }
    @Test fun lostArchiveResponseRestoresExpectedSnapshotWithoutAutomaticResubmission() = runBlocking {
        val s = create(); var calls = 0; var loseResponse = true
        suspend fun model(saved: SavedStateHandle): DetailsViewModel = withContext(Dispatchers.Main) {
            val owner = SubscriptionsViewModel(object : SubscriptionsSource {
                override fun activeDatasetId() = dataset
                override fun today() = day
                override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
                override fun details(id: UUID) = use.details(id)
                override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T {
                    calls++; val result = block(use)
                    if (loseResponse) { loseResponse = false; throw IOException("Synthetic response loss") }
                    return result
                }
            })
            owners += owner.viewModelScope.coroutineContext[Job]!!; store.put("owner",owner)
            DetailsViewModel(saved,owner,s.id).also { store.put("details",it) }
        }
        val saved = SavedStateHandle(); val vm = model(saved)
        withTimeout(5000) { vm.state.first { it.phase == DetailsPhase.Content } }
        withContext(Dispatchers.Main) { vm.open(DetailsAction.Archive); vm.submit(); vm.submit() }
        withTimeout(5000) { vm.state.first { it.frozen && it.error != null && it.phase != DetailsPhase.OperationInProgress } }
        assertEquals(1,calls)
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        clearModels()
        val retry = model(restored)
        withTimeout(5000) { retry.state.first { it.phase == DetailsPhase.Content } }
        assertEquals(1,calls); assertEquals(SubscriptionSnapshot.snapshot(s),restored.get<ArrayList<String>>("snapshot"))
        withContext(Dispatchers.Main) { retry.submit() }
        withTimeout(5000) { retry.state.first { it.conflicted } }
        assertEquals(2,calls); assertEquals(SubscriptionState.ARCHIVED,details(s.id).subscription.state)
    }
}
