package com.glebskorobogatov.subscriptiontracker.ui.history

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.refund.HistoryConflict
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import com.glebskorobogatov.subscriptiontracker.ui.details.detailsRow
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val store = ViewModelStore(); private var number = 0
    private val day = LocalDate.of(2027,1,31)
    private val empty = SubscriptionDetails(detailsRow(),LifecycleView(VisibleStatus.ACTIVE,null,null),emptyList(),emptyList())
    @Before fun start() { Dispatchers.setMain(dispatcher) }
    @After fun stop() { store.clear(); dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private fun model(saved: SavedStateHandle = SavedStateHandle(), values: Flow<SubscriptionDetails?> = flowOf(empty),
        operation: suspend () -> Unit = { error("Unknown database outcome") }): HistoryViewModel {
        val owner = SubscriptionsViewModel(object : SubscriptionsSource {
            override fun today() = day
            override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
            override fun details(id: UUID) = values
            override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T { operation(); error("Unknown database outcome") }
        })
        store.put("owner${number++}",owner)
        return HistoryViewModel(saved,owner,detailsRow().id).also { store.put("history${number++}",it) }
    }
    @Test fun loadingDoesNotBecomeEmptyBeforeFirstRoomEmission() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val vm = model(values = flow { gate.await(); emit(empty) })
        runCurrent(); assertEquals(HistoryPhase.Loading,vm.state.value.phase)
        gate.complete(Unit); runCurrent(); assertEquals(HistoryPhase.Empty,vm.state.value.phase)
    }
    @Test fun missingSubscriptionIsNotFound() = runTest(dispatcher) {
        val vm = model(values = flowOf(null)); runCurrent(); assertEquals(HistoryPhase.NotFound,vm.state.value.phase)
    }
    @Test fun databaseReadErrorNeverClaimsEmptyHistory() = runTest(dispatcher) {
        val vm = model(values = flow { throw IllegalStateException("Room") }); runCurrent(); assertEquals(HistoryPhase.Error,vm.state.value.phase)
    }
    @Test fun moneyDateAndCurrencyValidationDoesNotRound() = runTest(dispatcher) {
        val vm = model(); runCurrent()
        for((code,value) in listOf("JPY" to "123","RUB" to "1,23","USD" to "1.23","KWD" to "1,234"))
            assertTrue(vm.validate(mapOf("date" to day.toString(),"amount" to value,"currency" to code),day).isEmpty())
        for(value in listOf("","0","-1","1,234","999999999999999999999999"))
            assertTrue("amount" in vm.validate(mapOf("date" to day.toString(),"amount" to value,"currency" to "RUB"),day))
        val errors = vm.validate(mapOf("date" to day.plusDays(1).toString(),"amount" to "1","currency" to "BAD"),day)
        assertTrue("date" in errors); assertTrue("currency" in errors)
    }
    @Test fun unknownHistoricalOutcomeKeepsSameKeyAndPayloadOnRestore() = runTest(dispatcher) {
        val saved = SavedStateHandle(); var calls = 0
        val vm = model(saved,operation = { calls++ }); runCurrent()
        vm.open(HistoryAction.AddPayment); val key = saved.get<String>("newId")
        vm.submit(); runCurrent(); assertTrue(vm.state.value.frozen); assertNotNull(vm.state.value.error)
        vm.change("amount","999"); vm.dismiss(); assertNotNull(vm.state.value.action)
        val restored = SavedStateHandle(mapOf("action" to saved.get<String>("action"),"fields" to saved.get<HashMap<String,String>>("fields"),
            "newId" to key,"recordedAt" to saved.get<String>("recordedAt"),"sent" to true))
        val next = model(restored,operation = { calls++ }); runCurrent()
        assertEquals(1,calls) // restoration never resends automatically
        assertEquals(vm.state.value.fields,next.state.value.fields)
        next.submit(); runCurrent(); assertEquals(2,calls); assertEquals(key,restored.get<String>("newId"))
        assertTrue(next.state.value.frozen)
    }
    @Test fun doubleSubmitStartsOneOperation() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val vm = model(operation = { calls++; gate.await() }); runCurrent(); vm.open(HistoryAction.AddPayment)
        vm.submit(); vm.submit(); runCurrent(); assertEquals(1,calls); assertEquals(HistoryPhase.OperationInProgress,vm.state.value.phase)
        gate.complete(Unit); runCurrent(); assertTrue(vm.state.value.frozen)
    }
    @Test fun cancelledDeletionDoesNotCallRepository() = runTest(dispatcher) {
        val p = historyPayment(); var calls = 0
        val vm = model(values = flowOf(empty.copy(history = listOf(PaymentHistoryItem(p,emptyList(),Money(p.amountMinor,p.currencyCode))))),operation = { calls++ })
        runCurrent(); vm.open(HistoryAction.DeletePayment,p.id); vm.dismiss(); runCurrent()
        assertNull(vm.state.value.action); assertEquals(0,calls)
    }
    @Test fun domainRejectionKeepsFieldsAndAllowsCorrectionWithoutClaimingSuccess() = runTest(dispatcher) {
        val vm = model(operation = { throw IllegalArgumentException("Refund exceeds payment") }); runCurrent()
        vm.open(HistoryAction.AddPayment); vm.change("amount","1200"); val fields = vm.state.value.fields
        vm.submit(); runCurrent()
        assertEquals(HistoryAction.AddPayment,vm.state.value.action)
        assertEquals(fields,vm.state.value.fields)
        assertEquals(setOf("date","amount","currency"),vm.state.value.errors.keys)
        assertFalse(vm.state.value.frozen); assertNotNull(vm.state.value.error)
        vm.change("amount","1300"); assertEquals("1300",vm.state.value.fields["amount"])
        assertTrue(vm.state.value.errors.isEmpty())
    }
    @Test fun staleSnapshotRequiresExplicitRefreshWithoutAutomaticRetry() = runTest(dispatcher) {
        var calls = 0; val vm = model(operation = { calls++; throw HistoryConflict("stale") }); runCurrent()
        vm.open(HistoryAction.AddPayment); vm.submit(); runCurrent(); assertTrue(vm.state.value.conflicted)
        vm.submit(); runCurrent(); assertEquals(1,calls)
        vm.refresh(); runCurrent(); assertNull(vm.state.value.action); assertEquals(1,calls)
    }
    @Test fun removedSelectedPaymentAndSubscriptionRemainSafe() = runTest(dispatcher) {
        val p = historyPayment(); val values = MutableStateFlow<SubscriptionDetails?>(empty.copy(history = listOf(PaymentHistoryItem(p,emptyList(),Money(p.amountMinor,p.currencyCode)))))
        val vm = model(values = values); runCurrent(); vm.select(p.id)
        values.value = empty; runCurrent(); assertEquals(p.id,vm.state.value.selected); assertEquals(HistoryPhase.Empty,vm.state.value.phase)
        vm.select(null); values.value = null; runCurrent(); assertEquals(HistoryPhase.NotFound,vm.state.value.phase)
    }
    @Test fun refundDraftUsesPaymentCurrencyRatherThanNewSubscriptionPriceCurrency() = runTest(dispatcher) {
        val p = historyPayment().copy(currencyCode = CurrencyCode.of("USD"))
        val data = empty.copy(history = listOf(PaymentHistoryItem(p,emptyList(),Money(p.amountMinor,p.currencyCode))))
        val saved = SavedStateHandle(); val vm = model(saved,values = flowOf(data)); runCurrent(); vm.open(HistoryAction.AddRefund,p.id)
        assertEquals("USD",vm.state.value.fields["currency"])
        assertEquals(p,HistorySnapshots.payment(requireNotNull(saved.get<ArrayList<String>>("payment"))))
    }
}
