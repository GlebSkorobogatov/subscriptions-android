package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.forecast.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var index = 0
    private val rub = CurrencyCode.of("RUB")
    private val day = LocalDate.of(2027,1,15)
    @Before fun start() { Dispatchers.setMain(dispatcher) }
    @After fun end() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private fun snapshot(subscriptions: List<SubscriptionRecord> = listOf(sub(rub))): FinanceSnapshot =
        FinanceSnapshot(ForecastEngine().calculate(subscriptions,day),emptyList())
    private fun sub(currency: CurrencyCode) = SubscriptionRecord(SubscriptionId(UUID.nameUUIDFromBytes(currency.value.toByteArray())),
        "Сервис",RubPrice(12345),RecurringSchedule(RenewalAnchor.from(day),Frequency(FrequencyUnit.MONTH,1)))
    private fun model(source: FinanceSource,saved: SavedStateHandle = SavedStateHandle()) = FinanceViewModel(saved,source).also { store.put("vm${index++}",it) }
    private inner class Source(val values: MutableStateFlow<FinanceSnapshot>,var failRead: Boolean = false): FinanceSource {
        var calls = 0; var gate: CompletableDeferred<Unit>? = null
        override fun observe() = flow { if(failRead) error("read"); emitAll(values) }

    }
    @Test fun delayedFirstReadIsLoadingAndEmptyNeedsSuccessfulRead() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val result = snapshot(subscriptions = emptyList())
        val vm = model(object: FinanceSource {
            override fun observe() = flow { gate.await(); emit(result) }
        }); runCurrent(); assertEquals(FinancePhase.Loading,vm.state.value.phase)
        gate.complete(Unit); runCurrent(); assertEquals(FinancePhase.Empty,vm.state.value.phase)
    }
    @Test fun failedReadAndRetryNeverInventZeroForecast() = runTest(dispatcher) {
        val source = Source(MutableStateFlow(snapshot()),failRead = true); val vm = model(source); runCurrent()
        assertEquals(FinancePhase.Error,vm.state.value.phase); assertNull(vm.state.value.snapshot)
        source.failRead = false; vm.retry(); runCurrent(); assertEquals(FinancePhase.Content,vm.state.value.phase)
    }
    @Test fun retiredCurrencyAndHorizonSavedStateCannotWriteOrChangeRubProjection() = runTest(dispatcher) {
        val source = Source(MutableStateFlow(snapshot()))
        val saved = SavedStateHandle(mapOf("currency" to "USD","horizon" to 3,"unrelated" to "preserve"))
        val vm = model(source,saved); runCurrent()
        assertEquals(0,source.calls)
        assertEquals(12,vm.state.value.snapshot!!.forecast!!.months.size)
        assertEquals(12345L*12,vm.state.value.snapshot!!.forecast!!.totalMinor)
        assertFalse(saved.contains("currency")); assertFalse(saved.contains("horizon"))
        assertEquals("preserve",saved.get<String>("unrelated"))
    }

    @Test fun monthRestoresAndClampsOnlyWhenItLeavesWindow() = runTest(dispatcher) {
        val source = Source(MutableStateFlow(snapshot())); val saved = SavedStateHandle()
        val vm = model(source,saved); runCurrent(); vm.selectMonth(YearMonth.of(2027,11))
        val next = model(source,SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })); runCurrent()
        assertEquals(YearMonth.of(2027,11),next.state.value.month)
        source.values.value = FinanceSnapshot(ForecastEngine().calculate(listOf(sub(rub)),day.plusMonths(1)),emptyList())
        runCurrent(); assertEquals(YearMonth.of(2027,11),next.state.value.month)
        source.values.value = FinanceSnapshot(ForecastEngine().calculate(listOf(sub(rub)),day.plusMonths(11)),emptyList())
        runCurrent(); assertEquals(YearMonth.of(2027,12),next.state.value.month)
    }


    @Test fun overflowIsAnErrorWithNoPartialOrZeroTotal() = runTest(dispatcher) {
        val vm = model(object: FinanceSource {
            override fun observe() = flow<FinanceSnapshot> {
                emit(snapshot(subscriptions = listOf(sub(rub).copy(price = RubPrice(Long.MAX_VALUE)),
                    sub(rub).copy(id = SubscriptionId(UUID.randomUUID()),price = RubPrice(1)))))
            }
        }); runCurrent(); assertEquals(FinancePhase.Error,vm.state.value.phase); assertNull(vm.state.value.snapshot)
    }
    @Test fun removingRubDataDoesNotFallbackToForeignAmountsOrResetValidMonth() = runTest(dispatcher) {
        val source = Source(MutableStateFlow(snapshot())); val vm = model(source); runCurrent()
        vm.selectMonth(YearMonth.of(2027,2))
        source.values.value = snapshot(subscriptions = emptyList()); runCurrent()
        assertEquals(FinancePhase.Empty,vm.state.value.phase)
        assertEquals(YearMonth.of(2027,2),vm.state.value.month); assertEquals(0,source.calls)
        assertEquals(0L,vm.state.value.snapshot!!.forecast!!.totalMinor)
    }


    @Test fun selectingMonthNeverChangesEitherWindowOrSnapshot() = runTest(dispatcher) {
        val original = snapshot(); val vm = model(Source(MutableStateFlow(original))); runCurrent()
        vm.selectMonth(YearMonth.of(2027,3))
        assertSame(original,vm.state.value.snapshot)
        assertEquals(12345L,vm.state.value.snapshot!!.forecast!!.next30Days.totalMinor)
        vm.selectMonth(YearMonth.of(1999,1))
        assertEquals(YearMonth.of(2027,3),vm.state.value.month)
    }
    @Test fun futureActiveSeriesWithoutEventsHasTwelveZeroBucketsAndRetainsSelection() = runTest(dispatcher) {
        val value = sub(rub).copy(schedule = RecurringSchedule.starting(day.plusYears(2),Frequency(FrequencyUnit.MONTH,1)))
        val vm = model(Source(MutableStateFlow(snapshot(listOf(value)))),SavedStateHandle(mapOf("month" to "2027-05")))
        runCurrent()
        assertEquals(FinancePhase.Empty,vm.state.value.phase)
        assertEquals(YearMonth.of(2027,5),vm.state.value.month)
        assertEquals(12,vm.state.value.snapshot!!.forecast!!.months.size)
        assertTrue(vm.state.value.snapshot!!.forecast!!.months.values.all { it.totalMinor == 0L && it.charges.isEmpty() })
    }
    @Test fun retryRecalculatesAfterOverflowCauseIsCorrected() = runTest(dispatcher) {
        var row = sub(rub).copy(price = RubPrice(Long.MAX_VALUE))
        val vm = model(object: FinanceSource {
            override fun observe() = flow { emit(snapshot(listOf(row))) }
        }); runCurrent()
        assertEquals(FinancePhase.Error,vm.state.value.phase); assertNull(vm.state.value.snapshot)
        row = row.copy(price = RubPrice(29950)); vm.retry(); runCurrent()
        assertEquals(FinancePhase.Content,vm.state.value.phase)
        assertEquals(29950L,vm.state.value.snapshot!!.forecast!!.next30Days.totalMinor)
        assertEquals(359400L,vm.state.value.snapshot!!.forecast!!.totalMinor)
    }
    @Test fun calendarFailureIsErrorAndRetryRebuildsWholeSnapshot() = runTest(dispatcher) {
        var invalid = true
        val vm = model(object: FinanceSource {
            override fun observe() = flow<FinanceSnapshot> {
                if (invalid) throw CalendarCalculationException("Synthetic range failure")
                emit(snapshot())
            }
        }); runCurrent()
        assertEquals(FinancePhase.Error,vm.state.value.phase); assertNull(vm.state.value.snapshot)
        invalid = false; vm.retry(); runCurrent()
        assertEquals(FinancePhase.Content,vm.state.value.phase)
    }
    @Test fun cancellationIsNotPresentedAsCalculationError() = runTest(dispatcher) {
        val vm = model(object: FinanceSource {
            override fun observe() = flow<FinanceSnapshot> { throw CancellationException("Synthetic cancellation") }
        }); runCurrent()
        assertEquals(FinancePhase.Loading,vm.state.value.phase); assertNull(vm.state.value.snapshot)
    }

    @Test fun failedProjectionRetainsMonthAcrossRetryRecreationAndAutomaticRecovery() = runTest(dispatcher) {
        val good = snapshot()
        val source = Source(MutableStateFlow(good)); val saved = SavedStateHandle()
        val vm = model(source,saved); runCurrent(); vm.selectMonth(YearMonth.of(2027,5))
        source.values.value = FinanceSnapshot(com.glebskorobogatov.subscriptiontracker.data.integration.ForecastState.Error,emptyList(),day)
        runCurrent()
        assertEquals(FinancePhase.Error,vm.state.value.phase); assertNull(vm.state.value.snapshot!!.forecast)
        assertEquals(YearMonth.of(2027,5),vm.state.value.month)
        vm.retry(); runCurrent()
        assertEquals(FinancePhase.Error,vm.state.value.phase); assertNull(vm.state.value.snapshot!!.forecast)
        val restored = model(source,SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })); runCurrent()
        assertEquals(YearMonth.of(2027,5),restored.state.value.month)
        source.values.value = good; runCurrent()
        assertEquals(FinancePhase.Content,vm.state.value.phase); assertEquals(FinancePhase.Content,restored.state.value.phase)
        assertEquals(YearMonth.of(2027,5),restored.state.value.month)
        assertEquals(12345L,restored.state.value.snapshot!!.forecast!!.next30Days.totalMinor)
    }
    @Test fun failedSnapshotUsesItsOwnTodayToKeepOrClampMonthWithoutInventingTotals() = runTest(dispatcher) {
        val error = com.glebskorobogatov.subscriptiontracker.data.integration.ForecastState.Error
        val source = Source(MutableStateFlow(FinanceSnapshot(error,emptyList(),day)))
        val vm = model(source,SavedStateHandle(mapOf("month" to "2027-05"))); runCurrent()
        assertEquals(YearMonth.of(2027,5),vm.state.value.month)
        source.values.value = FinanceSnapshot(error,emptyList(),day.plusYears(1)); runCurrent()
        assertEquals(YearMonth.of(2028,1),vm.state.value.month)
        assertNull(vm.state.value.snapshot!!.forecast); assertEquals(FinancePhase.Error,vm.state.value.phase)
    }
}
