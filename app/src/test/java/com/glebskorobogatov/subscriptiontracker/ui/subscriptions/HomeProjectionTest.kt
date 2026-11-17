package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import androidx.lifecycle.ViewModelStore
import com.glebskorobogatov.subscriptiontracker.data.mapping.toEntity
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeProjectionTest {
    private val day = LocalDate.of(2027,1,31)
    private fun row(id: Long,due: LocalDate = day) = SubscriptionDomain().create(SubscriptionId(UUID(0,id)),"Одинаковый сервис",RubPrice(123),
        ScheduleEdit(due,Frequency(FrequencyUnit.MONTH,1)),day).toEntity(Instant.EPOCH)
    @Test fun duplicateNamesKeepIndependentStableIdsAndChronologicalOrdering() {
        val rows = listOf(row(3,day.plusDays(1)),row(2),row(1))
        val result = librarySnapshot(rows,day) as SubscriptionsUiState.Content
        assertEquals(listOf(UUID(0,1),UUID(0,2),UUID(0,3)),result.active.map { it.id })
        assertEquals(3,result.names.size)
    }
    @Test fun cancelledDatesFollowActiveWithUnknownDatesLastAndStableTies() {
        val rows = listOf(row(3).copy(state = SubscriptionState.CANCELLED),
            row(2).copy(state = SubscriptionState.CANCELLED,cancelledAccessEndExclusive = day.plusDays(1)),
            row(1).copy(state = SubscriptionState.CANCELLED,cancelledAccessEndExclusive = day.plusDays(1)),row(4))
        val result = librarySnapshot(rows,day) as SubscriptionsUiState.Content
        assertEquals(listOf(UUID(0,4)),result.active.map { it.id })
        assertEquals(listOf(UUID(0,1),UUID(0,2),UUID(0,3)),result.cancelled.map { it.id })
    }
    @Test fun invalidCalendarIsIsolatedAndSortedAfterKnownActiveDates() {
        val bad = row(1).copy(intervalUnit = FrequencyUnit.YEAR,intervalCount = Int.MAX_VALUE)
        val result = librarySnapshot(listOf(bad,row(2,day.plusDays(2))),day.plusDays(1)) as SubscriptionsUiState.Content
        assertEquals(listOf(UUID(0,2),UUID(0,1)),result.active.map { it.id })
        assertTrue(result.active.last().calendarError); assertNull(result.active.last().eventDate)
    }
    @Test fun archivedRowsAreAbsentFromLibraryButRemainInNamesForSafeNavigation() {
        val source = row(1).copy(state = SubscriptionState.ARCHIVED,archivedAt = day)
        val empty = librarySnapshot(listOf(source),day) as SubscriptionsUiState.Empty
        assertEquals(setOf(source.id),empty.names.keys)
    }
    @Test fun expiredCancelledSubscriptionStaysVisibleWithoutProgressOrMutation() {
        val source = row(1).copy(state = SubscriptionState.CANCELLED,cancelledAccessEndExclusive = day.minusDays(1),frozenCancelledPeriodStart = day.minusDays(31))
        val result = librarySnapshot(listOf(source),day) as SubscriptionsUiState.Content
        assertEquals(source.id,result.cancelled.single().id); assertNull(result.cancelled.single().remainingFraction)
        assertEquals(SubscriptionState.CANCELLED,source.state)
    }
    @Test fun rowsAndTodayReactWithoutCollectingAFullFinancialProjection() = runBlocking {
        val rows = MutableStateFlow(listOf(row(1).copy(priceMinor = Long.MAX_VALUE)))
        val dates = MutableStateFlow(day)
        val updates = Channel<SubscriptionsUiState.Content>(Channel.UNLIMITED)
        val job = launch { subscriptionStates(rows,dates).filterIsInstance<SubscriptionsUiState.Content>().collect { updates.send(it) } }
        try {
            assertEquals(Long.MAX_VALUE,withTimeout(5000) { updates.receive() }.active.single().price.amountMinor)
            rows.value = listOf(rows.value.single().copy(priceMinor = 199050))
            assertEquals(199050L,withTimeout(5000) { updates.receive() }.active.single().price.amountMinor)
            dates.value = day.plusDays(1)
            assertEquals(LocalDate.of(2027,2,28),withTimeout(5000) { updates.receive() }.active.single().eventDate)
        } finally { job.cancelAndJoin(); updates.close() }
    }
    @Test fun actualOwnerNeverCallsFinanceForLibraryAndStorageFailureStillBlocks() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val store = ViewModelStore(); var financeCalls = 0; var mutations = 0
        try {
            val owner = SubscriptionsViewModel(object: SubscriptionsSource {
                override fun observe(refreshes: Flow<Unit>) = subscriptionStates(flowOf(listOf(row(1).copy(priceMinor = Long.MAX_VALUE))),flowOf(day))
                override fun finance() = flow<com.glebskorobogatov.subscriptiontracker.ui.finance.FinanceSnapshot> { financeCalls++; error("Must not calculate") }
            }); store.put("owner",owner)
            assertEquals(Long.MAX_VALUE,(owner.state.first { it is SubscriptionsUiState.Content } as SubscriptionsUiState.Content).active.single().price.amountMinor)
            assertEquals(0,financeCalls)
            val blocked = SubscriptionsViewModel(object: SubscriptionsSource {
                override fun observe(refreshes: Flow<Unit>) = subscriptionStates(flow<List<SubscriptionEntity>> { throw StorageUnavailable(StorageFailure.CORRUPT) },flowOf(day))
                override suspend fun <T> perform(block: suspend (com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases) -> T): T { mutations++; error("Must not mutate") }
            }); store.put("blocked",blocked)
            blocked.state.first { it is SubscriptionsUiState.StorageBlocked }
            try { blocked.perform { }; fail("Blocked") } catch(_: IllegalStateException) { }
            assertEquals(0,mutations)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
