package com.glebskorobogatov.subscriptiontracker.ui.history

import android.content.Context
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.refund.HistoryConflict
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

class HistoryIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dataset = UUID.randomUUID()
    private lateinit var db: TrackerDatabase
    private lateinit var lease: DatasetSettingsFactory.Lease
    private lateinit var use: SubscriptionUseCases
    private val day = LocalDate.of(2027,1,31)
    private val now = Instant.parse("2027-01-31T12:00:00Z")
    private val rub = CurrencyCode.of("RUB")
    private val usd = CurrencyCode.of("USD")
    private var store = ViewModelStore()
    private val owners = mutableListOf<Job>()
    @Before fun open() = runBlocking { reopen() }
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
        context.deleteDatabase("subscriptions-$dataset.db")
        File(context.filesDir,"datastore/settings-$dataset.preferences_pb").delete(); Unit
    }
    private suspend fun create(label: String = "Личный") = use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Одинаковое имя",label,
        Money(49900,rub),RenewalMode.MANUAL,RenewalSchedule(RenewalAnchor.from(day.minusDays(2)),Frequency(FrequencyUnit.MONTH,1))),now)
    private suspend fun details(id: UUID) = withTimeout(5000) { use.details(id).first() }!!
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch(_: IllegalArgumentException) { } catch(_: IllegalStateException) { }
    }
    @Test fun historyCrudUpdatesLiveDetailsWithoutChangingForecastStatusOrOtherSubscription() = runBlocking {
        val s = create(); val other = create("Семейный")
        val forecast = use.forecast30Days.first()
        val events = Channel<SubscriptionDetails?>(Channel.UNLIMITED)
        val reader = launch { use.details(s.id).collect { events.send(it) } }
        try {
            assertTrue(withTimeout(5000) { events.receive() }!!.history.isEmpty())
            val a = use.addHistoricalPayment(OperationId.random(),SubscriptionId(s.id),day.minusDays(5),Money(99900,rub),now)
            val b = use.addHistoricalPayment(OperationId.random(),SubscriptionId(s.id),day.minusDays(1),Money(1000,usd),now)
            val r1 = use.addRefund(RefundEntity(UUID.randomUUID(),a.id,day.minusDays(4),30000,rub))
            val r2 = use.addRefund(RefundEntity(UUID.randomUUID(),a.id,day,20000,rub))
            val observed = withTimeout(5000) { events.receiveAsFlow().first { it?.history?.find { h -> h.payment.id == a.id }?.netExpense?.amountMinor == 49900L } }!!
            assertEquals(listOf(b.id,a.id),historyNewestFirst(observed.history).map { it.payment.id })
            val corrected = use.editPayment(HistorySnapshots.payment(HistorySnapshots.payment(a)),day.minusDays(6),Money(120000,rub))
            assertEquals(a.copy(paidOn = day.minusDays(6),amountMinor = 120000),corrected)
            use.editRefund(r1,day.minusDays(3),Money(40000,rub)); use.deleteRefund(r2)
            assertEquals(80000L,details(s.id).history.find { it.payment.id == a.id }!!.netExpense.amountMinor)
            use.deletePayment(corrected,true)
            assertEquals(listOf(b.id),details(s.id).history.map { it.payment.id })
            assertTrue(withContext(Dispatchers.IO) { db.refunds().forPayment(a.id) }.isEmpty())
            assertEquals(s,details(s.id).subscription); assertEquals(VisibleStatus.AWAITING_RENEWAL,details(s.id).lifecycle.status)
            assertEquals(other,details(other.id).subscription); assertTrue(details(other.id).history.isEmpty())
            assertEquals(forecast,use.forecast30Days.first())
        } finally { reader.cancelAndJoin(); events.close() }
    }
    @Test fun completeSavedSnapshotsRejectConcurrentEditsAndRetiredKeysSurviveReopen() = runBlocking {
        val s = create(); val op = OperationId.random()
        val p = use.addHistoricalPayment(op,SubscriptionId(s.id),day.minusDays(3),Money(1000,usd),now)
        val savedPayment = HistorySnapshots.payment(p)
        val r = use.addRefund(RefundEntity(UUID.randomUUID(),p.id,day.minusDays(1),300,usd))
        val savedRefund = HistorySnapshots.refund(r)
        val edited = use.editPayment(p,day.minusDays(4),Money(1100,usd))
        try { use.editPayment(HistorySnapshots.payment(savedPayment),day,Money(1200,usd)); fail() } catch(_: HistoryConflict) { }
        val changedRefund = use.editRefund(r,day,Money(200,usd))
        try { use.editRefund(HistorySnapshots.refund(savedRefund),day,Money(100,usd)); fail() } catch(_: HistoryConflict) { }
        rejected { use.editPayment(edited,day,Money(199,usd)) }
        rejected { use.editPayment(edited,day.minusDays(4),Money(1100,rub)) }
        assertEquals(900L,details(s.id).history.single().netExpense.amountMinor)
        db.close(); lease.close(); reopen()
        assertEquals(edited,use.addHistoricalPayment(op,SubscriptionId(s.id),p.paidOn,Money(1000,usd),now))
        use.deleteRefund(changedRefund); rejected { use.addRefund(r) }
        use.deletePayment(edited,true)
        rejected { use.addHistoricalPayment(op,SubscriptionId(s.id),p.paidOn,Money(1000,usd),now) }
        assertTrue(details(s.id).history.isEmpty()); assertEquals(s,details(s.id).subscription)
    }
    @Test fun committedPaymentAndRefundWithLostResponseReplayOnceAfterRestoringViewModel() = runBlocking {
        val s = create(); var calls = 0; var loseResponse = true
        suspend fun model(saved: SavedStateHandle): HistoryViewModel = withContext(Dispatchers.Main) {
            val owner = SubscriptionsViewModel(object : SubscriptionsSource {
                override fun today() = day
                override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
                override fun details(id: UUID) = use.details(id)
                override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T {
                    calls++; val result = block(use)
                    if(loseResponse) { loseResponse = false; throw IOException("Committed, response lost") }
                    return result
                }
            })
            owners += owner.viewModelScope.coroutineContext[Job]!!
            store.put("owner",owner)
            HistoryViewModel(saved,owner,s.id).also { store.put("history",it) }
        }
        suspend fun awaitState(stage: String,vm: HistoryViewModel,predicate: (HistoryUiState) -> Boolean) {
            try { withTimeout(5000) { vm.state.first(predicate) } }
            catch(e: TimeoutCancellationException) { throw AssertionError("History receipt stage=$stage calls=$calls loseResponse=$loseResponse state=${vm.state.value}",e) }
        }
        for(action in listOf(HistoryAction.AddPayment,HistoryAction.AddRefund)) {
            loseResponse = true
            val before = calls; val saved = SavedStateHandle(); val vm = model(saved)
            awaitState("$action-initial-load",vm) { it.phase in setOf(HistoryPhase.Empty,HistoryPhase.Content) }
            val parent = details(s.id).history.firstOrNull()?.payment
            withContext(Dispatchers.Main) {
                vm.open(action,parent?.id); vm.change("amount",if(action == HistoryAction.AddPayment) "999" else "300")
                vm.submit(); vm.submit()
            }
            awaitState("$action-lost-response",vm) { it.error != null && it.frozen && it.phase != HistoryPhase.OperationInProgress }
            assertEquals(before+1,calls)
            val key = saved.get<String>("newId")
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
            clearModels()
            val retry = model(restored)
            awaitState("$action-restored-load",retry) { it.phase in setOf(HistoryPhase.Empty,HistoryPhase.Content) }
            assertEquals(before+1,calls) // recreation never sends automatically
            assertEquals(key,restored.get<String>("newId"))
            withContext(Dispatchers.Main) { retry.submit() }
            awaitState("$action-replay",retry) { it.action == null }
            assertEquals(before+2,calls)
            val history = details(s.id).history.single()
            if(action == HistoryAction.AddPayment) { assertEquals(UUID.fromString(key),history.payment.operationId); assertTrue(history.refunds.isEmpty()) }
            else { assertEquals(UUID.fromString(key),history.refunds.single().id); assertEquals(69900L,history.netExpense.amountMinor) }
            assertEquals(s,details(s.id).subscription)
            clearModels()
        }
    }
}
