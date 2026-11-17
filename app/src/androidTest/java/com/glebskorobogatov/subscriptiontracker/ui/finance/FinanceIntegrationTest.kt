package com.glebskorobogatov.subscriptiontracker.ui.finance

import com.glebskorobogatov.subscriptiontracker.fixtures.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class FinanceIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dataset = UUID.randomUUID()
    private lateinit var db: TrackerDatabase
    private lateinit var lease: DatasetSettingsFactory.Lease
    private lateinit var use: SubscriptionUseCases
    private val day = LocalDate.of(2027,1,31)
    private val now = Instant.parse("2027-01-31T12:00:00Z")
    private val rub = CurrencyCode.of("RUB")
    @Before fun open() = runBlocking {
        authorizeFreshFixture(context, dataset)
        reopen()
    }
    private suspend fun reopen() {
        db = TrackerDatabaseFactory.open(context,dataset); lease = DatasetSettingsFactory.acquire(context,dataset)
        use = SubscriptionUseCases(db,lease.repository,CurrentDate(day))
    }
    @After fun close() = runBlocking {
        db.close(); lease.close(); context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }
        File(context.filesDir,"datastore/settings-$dataset.preferences_pb").delete(); Unit
    }
    private suspend fun create(label: String) = use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Одинаковое имя",label,RubPrice(100),ScheduleEdit(day,Frequency(FrequencyUnit.MONTH,1))),now)
    @Test fun fixedRubTwelveMonthsAndIndependentSubscriptionsSurviveReopen() = runBlocking {
        val a=create("Личный"); val b=create("Семейный")
        val actual=withTimeout(5000) { financeSnapshots(use).first() }
        assertEquals(12,actual.forecast.months.size); assertEquals(2400L,actual.forecast.totalMinor)
        assertEquals(ForecastEngine().calculate(listOf(a,b).map { it.toDomain() },day),actual.forecast)
        assertEquals(setOf(a.id,b.id),actual.forecast.charges.map { it.subscriptionId.value }.toSet())
        assertEquals(12,actual.forecast.charges.count { it.subscriptionId.value==b.id })
        db.close(); lease.close(); reopen()
        assertEquals(actual,withTimeout(5000) { financeSnapshots(use).first() })
    }
    @Test fun livePriceDateAndLifecycleChangesRecomputeOnlyActiveForecasts() = runBlocking {
        val a=create("Личный"); val b=create("Семейный")
        val updates=Channel<FinanceSnapshot>(Channel.UNLIMITED)
        val reader=launch { financeSnapshots(use).collect { updates.send(it) } }
        suspend fun await(predicate: (FinanceSnapshot)->Boolean) = withTimeout(5000) { updates.receiveAsFlow().first(predicate) }
        try {
            await { it.forecast.charges.size==24 }
            val edited=use.editSubscription(a,SubscriptionEdit(a.name,"Обновлённый",RubPrice(200)),now.plusSeconds(1))
            await { it.forecast.totalMinor==3600L }
            use.changeLifecycle(edited,LifecycleCommand.Cancel(),now.plusSeconds(2))
            await { it.forecast.charges.size==12 && it.forecast.charges.all { c -> c.subscriptionId.value==b.id } }
            use.changeLifecycle(b,LifecycleCommand.Archive,now.plusSeconds(3))
            await { it.forecast.charges.isEmpty() }
        } finally { reader.cancelAndJoin(); updates.close() }
    }
    @Test fun concurrentEditsCannotMixPriceLabelsEligibilityAndForecastSnapshots() = runBlocking {
        val row=create("Первый")
        val snapshots=mutableListOf<FinanceSnapshot>()
        val reader=launch { financeSnapshots(use).collect { snapshots+=it } }
        try {
            withTimeout(5000) { while(snapshots.isEmpty()) yield() }
            var current=row
            repeat(8) { index ->
                current=use.editSubscription(current,SubscriptionEdit(current.name,"Метка $index",RubPrice(101L+index)),now.plusSeconds(index.toLong()+1))
            }
            use.changeLifecycle(current,LifecycleCommand.Cancel(),now.plusSeconds(20))
            withTimeout(5000) { while(snapshots.last().rows.single().state!=SubscriptionState.CANCELLED) yield() }
            for (snapshot in snapshots) {
                val stored=snapshot.rows.single()
                assertTrue(snapshot.forecast.charges.all { it.amountMinor==stored.priceMinor && it.subscriptionId.value==stored.id })
                assertEquals(stored.state==SubscriptionState.ACTIVE,snapshot.forecast.charges.isNotEmpty())
            }
        } finally { reader.cancelAndJoin() }
    }

    private suspend fun snapshot() = withTimeout(5000) { financeSnapshots(use).first() }
    private fun coherent(snapshot: FinanceSnapshot) {
        val f = snapshot.forecast
        assertEquals(12,f.months.size)
        assertEquals(f.fromInclusive,f.next30Days.fromInclusive)
        assertEquals(f.fromInclusive.plusDays(31),f.next30Days.untilExclusive)
        assertEquals(f.charges.filter { it.dueDate < f.next30Days.untilExclusive },f.next30Days.charges)
        assertEquals(f.charges,f.months.values.flatMap { it.charges })
        assertEquals(f.totalMinor,f.months.values.sumOf { it.totalMinor })
        for (charge in f.charges) {
            val row = snapshot.rows.single { it.id == charge.subscriptionId.value }
            assertTrue(row.toDomain().participatesInCalculations)
            assertEquals(row.priceMinor,charge.amountMinor)
        }
        f.months.values.forEach { assertEquals(it.charges.sumOf { event -> event.amountMinor },it.totalMinor) }
    }
    @Test fun allMutationsUpdateBothCalendarWindowsAndPreserveSourceAnchor() = runBlocking {
        var row = create("Метка")
        assertEquals(200L,snapshot().forecast.next30Days.totalMinor)
        row = use.editSubscription(row,SubscriptionEdit(row.name,row.accountLabel,RubPrice(123)),now)
        var value = snapshot(); coherent(value)
        assertEquals(246L,value.forecast.next30Days.totalMinor); assertEquals(1476L,value.forecast.totalMinor)
        assertEquals(31,row.anchorDay)
        row = use.editSubscription(row,SubscriptionEdit(row.name,row.accountLabel,RubPrice(123),
            ScheduleEdit(day.plusDays(3),Frequency(FrequencyUnit.MONTH,1))),now)
        value = snapshot(); coherent(value)
        assertEquals(123L,value.forecast.next30Days.totalMinor)
        assertTrue(value.forecast.charges.all { it.dueDate.dayOfMonth == 3 })
        row = use.editSubscription(row,SubscriptionEdit(row.name,row.accountLabel,RubPrice(123),
            ScheduleEdit(day.plusDays(3),Frequency(FrequencyUnit.WEEK,1))),now)
        value = snapshot(); coherent(value)
        assertEquals(4,value.forecast.next30Days.charges.size)
        row = use.changeLifecycle(row,LifecycleCommand.Cancel(),now)
        assertEquals(0L,snapshot().forecast.totalMinor)
        row = use.changeLifecycle(row,LifecycleCommand.Reactivate(RubPrice(123),ScheduleEdit(day,Frequency(FrequencyUnit.DAY,2))),now)
        value = snapshot(); coherent(value); assertEquals(16,value.forecast.next30Days.charges.size)
        row = use.changeLifecycle(row,LifecycleCommand.Archive,now)
        assertEquals(0L,snapshot().forecast.next30Days.totalMinor)
        row = use.changeLifecycle(row,LifecycleCommand.Reactivate(RubPrice(29950),ScheduleEdit(day,Frequency(FrequencyUnit.MONTH,1))),now)
        value = snapshot(); coherent(value); assertEquals(59900L,value.forecast.next30Days.totalMinor)
        use.deleteSubscription(row,true)
        value = snapshot(); coherent(value); assertEquals(0L,value.forecast.totalMinor); assertTrue(value.rows.isEmpty())
    }
    @Test fun currentDateRefreshRebuildsBothWindowsAndMainProjectionWithoutRoomWrite() = runBlocking {
        val row = create("Сохранённая опора")
        val before = snapshot(); assertEquals(200L,before.forecast.next30Days.totalMinor)
        use.refreshToday(day.plusDays(1),now)
        val after = snapshot(); coherent(after)
        assertEquals(day.plusDays(1),after.forecast.fromInclusive)
        assertEquals(100L,after.forecast.next30Days.totalMinor)
        assertEquals(YearMonth.of(2027,2),after.forecast.months.keys.first())
        val main = com.glebskorobogatov.subscriptiontracker.ui.subscriptions.subscriptionStates(use).first()
            as com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.Content
        assertEquals(after.forecast.next30Days.totalMinor,main.forecast)
        assertEquals(row,after.rows.single())
    }
    @Test fun emptyAndFutureOnlySnapshotsKeepTwelveZeroBuckets() = runBlocking {
        for (future in listOf(false,true)) {
            if (future) use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Будущая серия",null,RubPrice(29950),
                ScheduleEdit(day.plusYears(2),Frequency(FrequencyUnit.MONTH,1))),now)
            val value = snapshot(); coherent(value)
            assertEquals(0L,value.forecast.next30Days.totalMinor); assertEquals(0L,value.forecast.totalMinor)
            assertTrue(value.forecast.months.values.all { it.totalMinor == 0L && it.charges.isEmpty() })
        }
    }
    @Test fun concurrentEditsKeepMetadataBothWindowsAndDetailsInSameSnapshot() = runBlocking {
        val row = create("Метка 100")
        val values = Channel<FinanceSnapshot>(Channel.UNLIMITED)
        val reader = launch { financeSnapshots(use).collect { values.send(it) } }
        suspend fun await(price: Long) = withTimeout(5000) { values.receiveAsFlow().first { it.rows.single().priceMinor == price } }
        try {
            coherent(await(100))
            var current = row
            repeat(10) { i ->
                val price = 101L+i
                current = use.editSubscription(current,SubscriptionEdit("Одинаковое имя","Метка $price",RubPrice(price)),now.plusSeconds(price))
                val value = await(price); coherent(value)
                assertEquals("Метка $price",value.rows.single().accountLabel)
                assertEquals(price*2,value.forecast.next30Days.totalMinor)
                assertEquals(price*12,value.forecast.totalMinor)
            }
        } finally { reader.cancelAndJoin(); values.close() }
    }
    @Test fun overflowIsScreenErrorRatherThanEmptyOrStorageBlockAndRetryCanRecover() = runBlocking {
        val row = create("Overflow")
        val bad = use.editSubscription(row,SubscriptionEdit(row.name,row.accountLabel,RubPrice(Long.MAX_VALUE)),now)
        val failed = com.glebskorobogatov.subscriptiontracker.ui.subscriptions.subscriptionStates(use).first()
        assertEquals(com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.Error,failed)
        try { snapshot(); fail("Overflow must not return a snapshot") } catch (_: ForecastCalculationFailure) { }
        use.editSubscription(bad,SubscriptionEdit(row.name,row.accountLabel,RubPrice(29950)),now)
        val recovered = snapshot(); coherent(recovered)
        assertEquals(59900L,recovered.forecast.next30Days.totalMinor)
        assertTrue(com.glebskorobogatov.subscriptiontracker.ui.subscriptions.subscriptionStates(use).first()
            is com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.Content)
    }

    @Test fun realViewModelRetryRecoversAfterOverflowWithoutPublishingZeroSnapshot() = runBlocking {
        val row = create("Ошибочный расчёт")
        val bad = use.editSubscription(row,SubscriptionEdit(row.name,row.accountLabel,RubPrice(Long.MAX_VALUE)),now)
        val store = androidx.lifecycle.ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            FinanceViewModel(androidx.lifecycle.SavedStateHandle(),object: FinanceSource {
                override fun observe() = financeSnapshots(use)
            }).also { store.put("finance",it) }
        }
        try {
            val failed = withTimeout(5000) { vm.state.first { it.phase == FinancePhase.Error } }
            assertNull(failed.snapshot)
            use.editSubscription(bad,SubscriptionEdit(row.name,row.accountLabel,RubPrice(29950)),now)
            withContext(Dispatchers.Main) { vm.retry() }
            val recovered = withTimeout(5000) { vm.state.first { it.phase == FinancePhase.Content } }
            val current = requireNotNull(recovered.snapshot)
            coherent(current)
            assertEquals(59900L,current.forecast.next30Days.totalMinor)
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }
}
