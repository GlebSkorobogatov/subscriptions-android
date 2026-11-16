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
    private fun snapshot(currency: CurrencyCode? = usd,months: Int = 3,subscriptions: List<Subscription> = listOf(sub(usd),sub(rub))): FinanceSnapshot =
        FinanceSnapshot(ForecastEngine().calendarMonths(subscriptions,day,months,currency),emptyList(),months,currency)
    private fun sub(currency: CurrencyCode) = Subscription(SubscriptionId(UUID.nameUUIDFromBytes(currency.value.toByteArray())),
        RenewalMode.MANUAL,RenewalSchedule(RenewalAnchor.from(day),Frequency(FrequencyUnit.MONTH,1)),Money(12345,currency))
    private fun model(source: FinanceSource,saved: SavedStateHandle = SavedStateHandle()) = FinanceViewModel(saved,source).also { store.put("vm${index++}",it) }
    private inner class Source(val values: MutableStateFlow<FinanceSnapshot>,var failRead: Boolean = false,var failWrite: Boolean = false): FinanceSource {
        var calls = 0; var gate: CompletableDeferred<Unit>? = null
        override fun observe() = flow { if(failRead) error("read"); emitAll(values) }
        override suspend fun select(horizon: Int,currency: CurrencyCode?) { calls++; gate?.await(); if(failWrite) error("write")
            values.value = snapshot(currency,horizon) }
        override suspend fun fallback(snapshot: FinanceSnapshot,currency: CurrencyCode) { select(snapshot.horizon,currency) }
    }
    @Test fun delayedFirstReadIsLoadingAndEmptyNeedsSuccessfulRead() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val result = snapshot(null,subscriptions = emptyList())
        val vm = model(object: FinanceSource {
            override fun observe() = flow { gate.await(); emit(result) }
            override suspend fun select(horizon: Int,currency: CurrencyCode?) {}
            override suspend fun fallback(snapshot: FinanceSnapshot,currency: CurrencyCode) {}
        }); runCurrent(); assertEquals(FinancePhase.Loading,vm.state.value.phase)
        gate.complete(Unit); runCurrent(); assertEquals(FinancePhase.Empty,vm.state.value.phase)
    }
    @Test fun failedReadAndRetryNeverInventZeroForecast() = runTest(dispatcher) {
        val source = Source(MutableStateFlow(snapshot()),failRead = true); val vm = model(source); runCurrent()
        assertEquals(FinancePhase.Error,vm.state.value.phase); assertNull(vm.state.value.snapshot)
        source.failRead = false; vm.retry(); runCurrent(); assertEquals(FinancePhase.Content,vm.state.value.phase)
    }
    @Test fun unavailableCurrencyFallsBackDeterministicallyAndIsSaved() = runTest(dispatcher) {
        val source = Source(MutableStateFlow(snapshot(CurrencyCode.of("JPY"))))
        val vm = model(source); runCurrent(); assertEquals(rub,vm.state.value.currency); assertEquals(1,source.calls)
        assertEquals(rub,source.values.value.currency); assertEquals(12345L,vm.state.value.snapshot!!.forecast.totalsByCurrency[rub]!!.amountMinor)
    }
    @Test fun monthRestoresAndClampsOnlyWhenItLeavesWindow() = runTest(dispatcher) {
        val source = Source(MutableStateFlow(snapshot(months = 12))); val saved = SavedStateHandle()
        val vm = model(source,saved); runCurrent(); vm.selectMonth(YearMonth.of(2027,11))
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val next = model(source,restored); runCurrent(); assertEquals(YearMonth.of(2027,11),next.state.value.month)
        source.values.value = snapshot(months = 3); runCurrent(); assertEquals(YearMonth.of(2027,1),next.state.value.month)
        source.values.value = source.values.value.copy(forecast = ForecastEngine().calendarMonths(listOf(sub(usd)),day.plusMonths(1),3,usd))
        runCurrent(); assertEquals(YearMonth.of(2027,2),next.state.value.month)
    }
    @Test fun selectionsUseRepositoryAndDoubleWriteIsBlockedWithoutDiscardingDataOnFailure() = runTest(dispatcher) {
        val source = Source(MutableStateFlow(snapshot()),failWrite = true); val vm = model(source); runCurrent()
        source.gate = CompletableDeferred(); vm.selectHorizon(6); vm.selectHorizon(12); runCurrent()
        assertEquals(1,source.calls); assertTrue(vm.state.value.saving)
        source.gate!!.complete(Unit); runCurrent(); assertTrue(vm.state.value.saveError); assertEquals(FinancePhase.Content,vm.state.value.phase)
        assertEquals(3,vm.state.value.snapshot!!.horizon)
        source.failWrite = false; vm.selectHorizon(6); runCurrent(); assertEquals(6,vm.state.value.snapshot!!.horizon)
        vm.selectCurrency(rub); runCurrent(); assertEquals(rub,vm.state.value.currency)
    }
    @Test fun overflowIsAnErrorWithNoPartialOrZeroTotal() = runTest(dispatcher) {
        val vm = model(object: FinanceSource {
            override fun observe() = flow<FinanceSnapshot> {
                emit(snapshot(subscriptions = listOf(sub(usd).copy(price = Money(Long.MAX_VALUE,usd)),
                    sub(usd).copy(id = SubscriptionId(UUID.randomUUID()),price = Money(1,usd)))))
            }
            override suspend fun select(horizon: Int,currency: CurrencyCode?) {}
            override suspend fun fallback(snapshot: FinanceSnapshot,currency: CurrencyCode) {}
        }); runCurrent(); assertEquals(FinancePhase.Error,vm.state.value.phase); assertNull(vm.state.value.snapshot)
    }
    @Test fun removingLastSelectedCurrencyReactsWithoutResettingValidMonth() = runTest(dispatcher) {
        var rows = listOf(sub(usd),sub(rub))
        var months = 6
        var currency: CurrencyCode? = usd
        val values = MutableStateFlow(snapshot(currency,months,rows))
        val source = object: FinanceSource {
            override fun observe() = values
            override suspend fun select(horizon: Int,currency: CurrencyCode?) {
                months = horizon
                values.value = snapshot(currency,months,rows)
            }
            override suspend fun fallback(snapshot: FinanceSnapshot,currency: CurrencyCode) {
                values.value = this@FinanceViewModelTest.snapshot(currency,months,rows)
            }
        }
        val vm = model(source); runCurrent(); vm.selectMonth(YearMonth.of(2027,2))
        rows = listOf(sub(rub)); values.value = snapshot(currency,months,rows); runCurrent()
        assertEquals(rub,vm.state.value.currency); assertEquals(YearMonth.of(2027,2),vm.state.value.month)
        assertEquals(listOf(rub),vm.state.value.snapshot!!.forecast.availableCurrencies)
        vm.selectHorizon(3); runCurrent(); assertEquals(YearMonth.of(2027,2),vm.state.value.month)
    }

}
