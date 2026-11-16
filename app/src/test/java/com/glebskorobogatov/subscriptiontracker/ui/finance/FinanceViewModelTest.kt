package com.glebskorobogatov.subscriptiontracker.ui.finance

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.forecast.*
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
    private val usd = CurrencyCode.of("USD"); private val rub = CurrencyCode.of("RUB")
    private val day = LocalDate.of(2027,1,15)
    @Before fun start() { Dispatchers.setMain(dispatcher) }
    @After fun end() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private fun snapshot(currency: CurrencyCode? = rub,months: Int = 12,subscriptions: List<Subscription> = listOf(sub(usd),sub(rub))): FinanceSnapshot =
        FinanceSnapshot(ForecastEngine().calendarMonths(subscriptions,day,months,currency),emptyList())
    private fun sub(currency: CurrencyCode) = Subscription(SubscriptionId(UUID.nameUUIDFromBytes(currency.value.toByteArray())),
        RenewalMode.MANUAL,RenewalSchedule(RenewalAnchor.from(day),Frequency(FrequencyUnit.MONTH,1)),Money(12345,currency))
    private fun model(source: FinanceSource,saved: SavedStateHandle = SavedStateHandle()) = FinanceViewModel(saved,source).also { store.put("vm${index++}",it) }
    private inner class Source(val values: MutableStateFlow<FinanceSnapshot>,var failRead: Boolean = false): FinanceSource {
        var calls = 0; var gate: CompletableDeferred<Unit>? = null
        override fun observe() = flow { if(failRead) error("read"); emitAll(values) }

    }
    @Test fun delayedFirstReadIsLoadingAndEmptyNeedsSuccessfulRead() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val result = snapshot(null,subscriptions = emptyList())
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
        assertEquals(rub,vm.state.value.currency); assertEquals(0,source.calls)
        assertEquals(12,vm.state.value.snapshot!!.horizon)
        assertEquals(12345L,vm.state.value.snapshot!!.forecast.totalsByCurrency[rub]!!.amountMinor)
        assertFalse(saved.contains("currency")); assertFalse(saved.contains("horizon"))
        assertEquals("preserve",saved.get<String>("unrelated"))
    }

    @Test fun monthRestoresAndClampsOnlyWhenItLeavesWindow() = runTest(dispatcher) {
        val source = Source(MutableStateFlow(snapshot())); val saved = SavedStateHandle()
        val vm = model(source,saved); runCurrent(); vm.selectMonth(YearMonth.of(2027,11))
        val next = model(source,SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })); runCurrent()
        assertEquals(YearMonth.of(2027,11),next.state.value.month)
        source.values.value = source.values.value.copy(forecast = ForecastEngine().calendarMonths(listOf(sub(rub)),day.plusMonths(1),12,rub))
        runCurrent(); assertEquals(YearMonth.of(2027,11),next.state.value.month)
        source.values.value = source.values.value.copy(forecast = ForecastEngine().calendarMonths(listOf(sub(rub)),day.plusMonths(11),12,rub))
        runCurrent(); assertEquals(YearMonth.of(2027,12),next.state.value.month)
    }


    @Test fun overflowIsAnErrorWithNoPartialOrZeroTotal() = runTest(dispatcher) {
        val vm = model(object: FinanceSource {
            override fun observe() = flow<FinanceSnapshot> {
                emit(snapshot(subscriptions = listOf(sub(rub).copy(price = Money(Long.MAX_VALUE,rub)),
                    sub(rub).copy(id = SubscriptionId(UUID.randomUUID()),price = Money(1,rub)))))
            }
        }); runCurrent(); assertEquals(FinancePhase.Error,vm.state.value.phase); assertNull(vm.state.value.snapshot)
    }
    @Test fun removingRubDataDoesNotFallbackToForeignAmountsOrResetValidMonth() = runTest(dispatcher) {
        val source = Source(MutableStateFlow(snapshot())); val vm = model(source); runCurrent()
        vm.selectMonth(YearMonth.of(2027,2))
        source.values.value = snapshot(subscriptions = listOf(sub(usd))); runCurrent()
        assertEquals(FinancePhase.Empty,vm.state.value.phase); assertNull(vm.state.value.currency)
        assertEquals(YearMonth.of(2027,2),vm.state.value.month); assertEquals(0,source.calls)
        assertNull(vm.state.value.snapshot!!.forecast.totalsByCurrency[rub])
    }

}
