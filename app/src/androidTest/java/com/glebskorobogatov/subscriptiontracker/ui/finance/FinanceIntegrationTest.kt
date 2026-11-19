package com.glebskorobogatov.subscriptiontracker.ui.finance

import com.glebskorobogatov.subscriptiontracker.fixtures.*
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.glebskorobogatov.subscriptiontracker.app.SubscriptionsComposition
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsViewModel
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
import java.util.concurrent.atomic.AtomicReference
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
        assertEquals(12,actual.forecast!!.months.size); assertEquals(2400L,actual.forecast!!.totalMinor)
        assertEquals(ForecastEngine().calculate(listOf(a,b).map { it.toDomain() },day),actual.forecast)
        assertEquals(setOf(a.id,b.id),actual.forecast!!.charges.map { it.subscriptionId.value }.toSet())
        assertEquals(12,actual.forecast!!.charges.count { it.subscriptionId.value==b.id })
        assertEquals(2400L, actual.normalizedCosts!!.total.annualMinor)
        assertEquals(listOf(a.id, b.id).sortedBy(UUID::toString), actual.normalizedCosts!!.ranked.map { it.subscriptionId.value })
        db.close(); lease.close(); reopen()
        assertEquals(actual,withTimeout(5000) { financeSnapshots(use).first() })
    }
    @Test fun livePriceDateAndLifecycleChangesRecomputeOnlyActiveForecasts(): Unit = runBlocking {
        val a=create("Личный"); val b=create("Семейный")
        val updates=Channel<FinanceSnapshot>(Channel.UNLIMITED)
        val reader=launch { financeSnapshots(use).collect { updates.send(it) } }
        suspend fun await(predicate: (FinanceSnapshot)->Boolean) = withTimeout(5000) { updates.receiveAsFlow().first(predicate) }
        try {
            assertEquals(2400L, await { it.forecast!!.charges.size==24 }.normalizedCosts!!.total.annualMinor)
            val edited=use.editSubscription(a,SubscriptionEdit(a.name,"Обновлённый",RubPrice(200)),now.plusSeconds(1))
            val changed = await { it.forecast!!.totalMinor==3600L }
            assertEquals(3600L, changed.normalizedCosts!!.total.annualMinor)
            assertEquals("Обновлённый", changed.rows.single { it.id == a.id }.accountLabel)
            assertEquals(a.id, changed.normalizedCosts!!.ranked.first().subscriptionId.value)
            use.changeLifecycle(edited,LifecycleCommand.Cancel(),now.plusSeconds(2))
            val cancelled = await { it.forecast!!.charges.size==12 && it.forecast!!.charges.all { c -> c.subscriptionId.value==b.id } }
            assertEquals(b.id, cancelled.normalizedCosts!!.ranked.single().subscriptionId.value)
            assertEquals(1200L, cancelled.normalizedCosts!!.total.annualMinor)
            use.changeLifecycle(b,LifecycleCommand.Archive,now.plusSeconds(3))
            val archived = await { it.forecast!!.charges.isEmpty() }
            assertEquals(0L, archived.normalizedCosts!!.total.annualMinor)
            assertTrue(archived.normalizedCosts!!.ranked.isEmpty())
        } finally { reader.cancelAndJoin(); updates.close() }
        Unit
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
                assertTrue(snapshot.forecast!!.charges.all { it.amountMinor==stored.priceMinor && it.subscriptionId.value==stored.id })
                val active = stored.state == SubscriptionState.ACTIVE
                assertEquals(active, snapshot.forecast!!.charges.isNotEmpty())
                assertEquals(if (active) stored.priceMinor * 12 else 0L, snapshot.normalizedCosts!!.total.annualMinor)
                if (active) assertEquals(stored.id, snapshot.normalizedCosts!!.ranked.single().subscriptionId.value)
                else assertTrue(snapshot.normalizedCosts!!.ranked.isEmpty())
            }
        } finally { reader.cancelAndJoin() }
    }

    private suspend fun snapshot() = withTimeout(5000) { financeSnapshots(use).first() }
    private fun coherent(snapshot: FinanceSnapshot) {
        val f = requireNotNull(snapshot.forecast)
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
        assertEquals(200L,snapshot().forecast!!.next30Days.totalMinor)
        row = use.editSubscription(row,SubscriptionEdit(row.name,row.accountLabel,RubPrice(123)),now)
        var value = snapshot(); coherent(value)
        assertEquals(246L,value.forecast!!.next30Days.totalMinor); assertEquals(1476L,value.forecast!!.totalMinor)
        assertEquals(31,row.anchorDay)
        val unchangedTariff = value.normalization
        row = use.editSubscription(row,SubscriptionEdit(row.name,row.accountLabel,RubPrice(123),
            ScheduleEdit(day.plusDays(3),Frequency(FrequencyUnit.MONTH,1))),now)
        value = snapshot(); coherent(value)
        assertEquals(123L,value.forecast!!.next30Days.totalMinor)
        assertTrue(value.forecast!!.charges.all { it.dueDate.dayOfMonth == 3 })
        assertEquals(unchangedTariff, value.normalization)
        row = use.editSubscription(row,SubscriptionEdit(row.name,row.accountLabel,RubPrice(123),
            ScheduleEdit(day.plusDays(3),Frequency(FrequencyUnit.WEEK,1))),now)
        value = snapshot(); coherent(value)
        assertEquals(4,value.forecast!!.next30Days.charges.size)
        assertEquals(6414L, value.normalizedCosts!!.total.annualMinor)
        row = use.changeLifecycle(row,LifecycleCommand.Cancel(),now)
        assertEquals(0L,snapshot().forecast!!.totalMinor)
        row = use.changeLifecycle(row,LifecycleCommand.Reactivate(RubPrice(123),ScheduleEdit(day,Frequency(FrequencyUnit.DAY,2))),now)
        value = snapshot(); coherent(value); assertEquals(16,value.forecast!!.next30Days.charges.size)
        assertEquals(22448L, value.normalizedCosts!!.total.annualMinor)
        row = use.changeLifecycle(row,LifecycleCommand.Archive,now)
        assertEquals(0L,snapshot().forecast!!.next30Days.totalMinor)
        row = use.changeLifecycle(row,LifecycleCommand.Reactivate(RubPrice(29950),ScheduleEdit(day,Frequency(FrequencyUnit.MONTH,1))),now)
        value = snapshot(); coherent(value); assertEquals(59900L,value.forecast!!.next30Days.totalMinor)
        assertEquals(359400L, value.normalizedCosts!!.total.annualMinor)
        use.deleteSubscription(row,true)
        value = snapshot(); coherent(value); assertEquals(0L,value.forecast!!.totalMinor); assertTrue(value.rows.isEmpty())
        assertEquals(0L, value.normalizedCosts!!.total.annualMinor)
        assertTrue(value.normalizedCosts!!.ranked.isEmpty())
    }
    @Test fun currentDateRefreshRebuildsBothWindowsAndMainProjectionWithoutRoomWrite() = runBlocking {
        val row = create("Сохранённая опора")
        val before = snapshot(); assertEquals(200L,before.forecast!!.next30Days.totalMinor)
        use.refreshToday(day.plusDays(1),now)
        val after = snapshot(); coherent(after)
        assertEquals(day.plusDays(1),after.forecast!!.fromInclusive)
        assertEquals(100L,after.forecast!!.next30Days.totalMinor)
        assertEquals(YearMonth.of(2027,2),after.forecast!!.months.keys.first())
        val main = com.glebskorobogatov.subscriptiontracker.ui.subscriptions.subscriptionStates(use).first()
            as com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.Content
        assertEquals(row.id,main.active.single().id)
        assertEquals(LocalDate.of(2027,2,28),main.active.single().eventDate)
        assertEquals(row,after.rows.single())
        assertEquals(before.normalization, after.normalization)
    }
    @Test fun emptyAndFutureOnlySnapshotsKeepTwelveZeroBuckets() = runBlocking {
        for (future in listOf(false,true)) {
            if (future) use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Будущая серия",null,RubPrice(29950),
                ScheduleEdit(day.plusYears(2),Frequency(FrequencyUnit.MONTH,1))),now)
            val value = snapshot(); coherent(value)
            assertEquals(0L,value.forecast!!.next30Days.totalMinor); assertEquals(0L,value.forecast!!.totalMinor)
            assertTrue(value.forecast!!.months.values.all { it.totalMinor == 0L && it.charges.isEmpty() })
            assertEquals(if (future) 359400L else 0L, value.normalizedCosts!!.total.annualMinor)
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
                assertEquals(price*2,value.forecast!!.next30Days.totalMinor)
                assertEquals(price*12,value.forecast!!.totalMinor)
                assertEquals(price*12, value.normalizedCosts!!.total.annualMinor)
                assertEquals(price, value.normalizedCosts!!.ranked.single().amounts.monthlyMinor)
            }
        } finally { reader.cancelAndJoin(); values.close() }
    }
    @Test fun overflowIsScreenErrorRatherThanEmptyOrStorageBlockAndRetryCanRecover() = runBlocking {
        val row = create("Overflow")
        val bad = use.editSubscription(row,SubscriptionEdit(row.name,row.accountLabel,RubPrice(Long.MAX_VALUE)),now)
        val failed = com.glebskorobogatov.subscriptiontracker.ui.subscriptions.subscriptionStates(use).first()
        assertTrue(failed is com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.Content)
        assertEquals(bad.id,(failed as com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.Content).active.single().id)
        assertNull(snapshot().forecast)
        assertEquals(bad,snapshot().rows.single())
        use.editSubscription(bad,SubscriptionEdit(row.name,row.accountLabel,RubPrice(29950)),now)
        val recovered = snapshot(); coherent(recovered)
        assertEquals(59900L,recovered.forecast!!.next30Days.totalMinor)
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
            assertNull(failed.snapshot?.forecast)
            assertEquals(bad,failed.snapshot!!.rows.single())
            use.editSubscription(bad,SubscriptionEdit(row.name,row.accountLabel,RubPrice(29950)),now)
            withContext(Dispatchers.Main) { vm.retry() }
            val recovered = withTimeout(5000) { vm.state.first { it.phase == FinancePhase.Content } }
            val current = requireNotNull(recovered.snapshot)
            coherent(current)
            assertEquals(59900L,current.forecast!!.next30Days.totalMinor)
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }

    @Test fun competingEditsPublishOnlyTheWinningPriceAndLabelInBothCalculations(): Unit = runBlocking {
        val original = create("Исходная подпись")
        val gate = CompletableDeferred<Unit>()
        suspend fun edit(label: String, price: Long): SubscriptionEntity? = try {
            gate.await()
            use.editSubscription(original, SubscriptionEdit(original.name, label, RubPrice(price)), now)
        } catch (_: LifecycleConflict) { null }
        val a = async(Dispatchers.Default) { edit("Первое изменение", 150) }
        val b = async(Dispatchers.Default) { edit("Второе изменение", 250) }
        gate.complete(Unit)
        val winner = listOfNotNull(a.await(), b.await()).single()
        val value = snapshot()
        coherent(value)
        assertEquals(winner, value.rows.single())
        assertEquals(winner.priceMinor * 12, value.forecast!!.totalMinor)
        assertEquals(winner.priceMinor * 12, value.normalizedCosts!!.total.annualMinor)
        assertEquals(winner.id, value.normalizedCosts!!.ranked.single().subscriptionId.value)
    }

    @Test fun normalizationOnlyOverflowRecoversAfterPriceEditWithoutRestartingRoomObserver(): Unit = runBlocking {
        val row = use.createSubscription(SubscriptionDraft(UUID.randomUUID(), "Пограничный тариф", "Первая подпись",
            RubPrice(Long.MAX_VALUE), ScheduleEdit(day.withMonth(12), Frequency(FrequencyUnit.MONTH, 1))), now)
        val snapshots = Channel<FinanceSnapshot>(Channel.UNLIMITED)
        val collector = launch { financeSnapshots(use).collect { snapshots.send(it) } }
        try {
            val failed = withTimeout(5000) { snapshots.receive() }
            assertEquals(row, failed.rows.single())
            assertEquals(Long.MAX_VALUE, failed.forecast!!.totalMinor)
            assertEquals(NormalizedCostState.Overflow, failed.normalization)
            assertNull(failed.normalizedCosts)
            val repaired = use.editSubscription(row, SubscriptionEdit(row.name, "После исправления", RubPrice(29950)), now)
            val recovered = withTimeout(5000) { snapshots.receiveAsFlow().first { it.rows.single() == repaired } }
            assertEquals(29950L, recovered.forecast!!.totalMinor)
            assertEquals(359400L, recovered.normalizedCosts!!.total.annualMinor)
            assertEquals("После исправления", recovered.rows.single().accountLabel)
        } finally { collector.cancelAndJoin(); snapshots.close() }
    }

    @Test fun changingGenerationReplacesBothCalculationsAndMetadataTogether(): Unit = runBlocking {
        val first = create("Первая база")
        lease.repository.update { it.copy(costRankingPeriod = CostRankingPeriod.YEAR) }
        val replacement = UUID.randomUUID()
        val generation = AtomicReference(dataset)
        authorizeFreshFixture(context, replacement)
        val other = TrackerDatabaseFactory.open(context, replacement)
        val thisMonth = LocalDate.now().withDayOfMonth(1)
        val second = first.copy(id = UUID.randomUUID(), priceMinor = 350, accountLabel = "Вторая база",
            anchorYear = thisMonth.year, anchorMonth = thisMonth.monthValue, anchorDay = 1)
        try { withContext(Dispatchers.IO) { other.subscriptions().insert(second) } } finally { other.close() }
        val store = androidx.lifecycle.ViewModelStore()
        val jobs = mutableListOf<Job>()
        try {
            val (owner, model) = withContext(Dispatchers.Main) {
                val source = SubscriptionsComposition(context, activeDataset = generation::get)
                val owner = SubscriptionsViewModel(source).also { store.put("owner", it) }
                val finance = FinanceViewModel(androidx.lifecycle.SavedStateHandle(), owner).also { store.put("finance", it) }
                jobs += requireNotNull(owner.viewModelScope.coroutineContext[Job])
                jobs += requireNotNull(finance.viewModelScope.coroutineContext[Job])
                owner to finance
            }
            val before = withTimeout(5000) { model.state.first { it.snapshot?.rows?.singleOrNull()?.id == first.id } }
            assertEquals(1200L, before.snapshot!!.normalizedCosts!!.total.annualMinor)
            assertEquals(CostRankingPeriod.YEAR, withTimeout(5000) { owner.settings().first() }.costRankingPeriod)
            generation.set(replacement)
            try {
                owner.perform { fail("Stale generation must reject mutations") }
                fail("Expected stale generation")
            } catch (error: StorageUnavailable) {
                assertEquals(StorageFailure.STALE_GENERATION, error.failure)
            }
            withContext(Dispatchers.Main) { owner.retry() }
            val after = withTimeout(5000) { model.state.first { it.snapshot?.rows?.singleOrNull()?.id == second.id } }
            val value = requireNotNull(after.snapshot)
            assertEquals(replacement, owner.activeDatasetId())
            assertEquals(CostRankingPeriod.MONTH, withTimeout(5000) { owner.settings().first() }.costRankingPeriod)
            assertEquals(second, value.rows.single())
            val monthsWithCharges = if (value.today.dayOfMonth == 1) 12 else 11
            assertEquals(350L * monthsWithCharges, value.forecast!!.totalMinor)
            assertTrue(value.forecast!!.charges.all { it.subscriptionId.value == second.id })
            assertEquals(4200L, value.normalizedCosts!!.total.annualMinor)
            assertEquals(second.id, value.normalizedCosts!!.ranked.single().subscriptionId.value)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            jobs.joinAll()
            context.deleteDatabase("subscriptions-$replacement.db")
            forgetFixtureInitialization(context, "subscriptions-$replacement.db")
            File(context.filesDir,"datastore/settings-$replacement.preferences_pb").delete()
        }
    }

}
