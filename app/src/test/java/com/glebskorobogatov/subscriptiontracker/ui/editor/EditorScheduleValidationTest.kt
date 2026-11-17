package com.glebskorobogatov.subscriptiontracker.ui.editor

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.toEntity
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** JVM isolates the already-loaded row; Android integration tests exercise guarded loading and writes.
 * Reflection is fixture setup only, so production storage/session APIs need no testing bypass. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorScheduleValidationTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val domain = SubscriptionDomain()
    private var today = LocalDate.of(2026,11,8)
    private var index = 0
    @Before fun start() { Dispatchers.setMain(dispatcher) }
    @After fun stop() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private fun row(unit: FrequencyUnit, first: LocalDate = LocalDate.of(2026,11,1),
        count: Int = 1, anchorDay: Int? = null, state: SubscriptionState = SubscriptionState.ACTIVE): SubscriptionEntity {
        val record = domain.create(SubscriptionId.random(),"Сервис",RubPrice(100),ScheduleEdit(first,Frequency(unit,count),anchorDay),first)
        val stored = when(state) {
            SubscriptionState.ACTIVE -> record
            SubscriptionState.CANCELLED -> domain.cancel(record,today)
            SubscriptionState.ARCHIVED -> domain.archive(record,today,true)
        }
        return stored.toEntity(Instant.EPOCH)
    }
    private fun loaded(row: SubscriptionEntity): Pair<EditorViewModel,Map<String,String>> {
        val owner = SubscriptionsViewModel(object: SubscriptionsSource {
            override fun today() = today
            override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
        })
        store.put("owner${index++}",owner)
        val vm = EditorViewModel(SavedStateHandle(),owner,null)
        store.put("editor${index++}",vm)
        dispatcher.scheduler.runCurrent()
        EditorViewModel::class.java.getDeclaredField("expected").apply { isAccessible = true }.set(vm,row)
        val fromRow = EditorViewModel::class.java.getDeclaredMethod("fromRow",SubscriptionEntity::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return vm to (fromRow.invoke(vm,row) as Map<String,String>)
    }
    @Test fun weeklyPriceEditDoesNotTreatComputedDueAndInheritedDayAsNewSeries() = runTest(dispatcher) {
        val (vm,f) = loaded(row(FrequencyUnit.WEEK))
        assertEquals("2026-11-08",f["date"]); assertEquals("1",f["anchorDay"])
        assertEquals(emptyMap<String,Int>(),vm.validate(f + ("amount" to "2,34")))
    }
    @Test fun dailyNameEditAfterSeveralCyclesDoesNotRebuildSchedule() = runTest(dispatcher) {
        val (vm,f) = loaded(row(FrequencyUnit.DAY,count = 2))
        assertEquals("2026-11-09",f["date"])
        assertEquals(emptyMap<String,Int>(),vm.validate(f + ("name" to "Новое имя")))
    }
    @Test fun dailyAndWeeklyEditsAcrossMonthKeepInheritedAnchorValid() = runTest(dispatcher) {
        today = LocalDate.of(2026,12,3)
        for ((unit,count) in listOf(FrequencyUnit.DAY to 5,FrequencyUnit.WEEK to 1)) {
            val (vm,f) = loaded(row(unit,count = count))
            assertEquals("2026-12-06",f["date"]); assertEquals("1",f["anchorDay"])
            assertTrue(vm.validate(f + ("label" to "Аккаунт")).isEmpty())
        }
    }
    @Test fun inactiveDailyAndWeeklyRecordsDoNotRequireNewSchedule() = runTest(dispatcher) {
        for (state in listOf(SubscriptionState.CANCELLED,SubscriptionState.ARCHIVED)) for (unit in listOf(FrequencyUnit.DAY,FrequencyUnit.WEEK)) {
            val (vm,f) = loaded(row(unit,state = state))
            assertTrue(vm.validate(f + ("amount" to "3,45")).isEmpty())
        }
    }
    @Test fun staleUnchangedDisplayedDueDoesNotBlockOtherFields() = runTest(dispatcher) {
        val (vm,f) = loaded(row(FrequencyUnit.WEEK))
        today = today.plusMonths(2)
        assertTrue(vm.validate(f + ("name" to "Черновик")).isEmpty())
    }
    @Test fun monthly31AndLeapYear29RemainValidWhileExplicitInvalidAnchorIsRejected() = runTest(dispatcher) {
        today = LocalDate.of(2029,2,10)
        val cases = listOf(row(FrequencyUnit.MONTH,LocalDate.of(2027,1,31)),row(FrequencyUnit.YEAR,LocalDate.of(2028,2,29)))
        for (row in cases) {
            val (vm,f) = loaded(row)
            assertEquals("2029-02-28",f["date"])
            assertTrue(vm.validate(f + ("amount" to "5,67")).isEmpty())
            assertTrue("anchorDay" in vm.validate(f + ("anchorDay" to "32")))
        }
    }
}
