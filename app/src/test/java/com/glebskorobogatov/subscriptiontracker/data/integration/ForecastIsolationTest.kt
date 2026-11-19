package com.glebskorobogatov.subscriptiontracker.data.integration

import com.glebskorobogatov.subscriptiontracker.data.mapping.toEntity
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class ForecastIsolationTest {
    private val today = LocalDate.of(2027,1,1)
    private fun row(id: Long = 1, price: Long = 100, unit: FrequencyUnit = FrequencyUnit.MONTH, count: Int = 1) =
        SubscriptionDomain().create(SubscriptionId(UUID(0,id)),"Сервис $id",RubPrice(price),
            ScheduleEdit(today,Frequency(unit,count)),today).toEntity(Instant.EPOCH)
    @Test fun overflowSnapshotRetainsRowsAndAccessibleLibraryInsteadOfFalseZero() = runTest {
        val rows = listOf(row(price = Long.MAX_VALUE),row(id = 2))
        val snapshot = forecastSnapshot(rows,today)
        assertEquals(rows,snapshot.rows)
        assertNull(snapshot.forecast)
        val main = subscriptionStates(flowOf(snapshot.rows),flowOf(snapshot.today)).first() as SubscriptionsUiState.Content
        assertEquals(rows.map { it.id }.toSet(),main.active.map { it.id }.toSet())
        assertEquals(ForecastState.Error,snapshot.state)
    }
    @Test fun unrepresentableDateRetainsProblemRecordAndOtherSubscriptions() = runTest {
        val bad = row(unit = FrequencyUnit.YEAR,count = Int.MAX_VALUE)
        val good = row(id = 2)
        val snapshot = forecastSnapshot(listOf(bad,good),today.plusDays(1))
        val main = subscriptionStates(flowOf(snapshot.rows),flowOf(snapshot.today)).first() as SubscriptionsUiState.Content
        assertEquals(2,main.active.size)
        assertNull(main.active.single { it.id == bad.id }.eventDate)
        assertEquals(good.name,main.active.single { it.id == good.id }.name)
        assertEquals(ForecastState.Error,snapshot.state)
        assertEquals(1200L, snapshot.normalizedCosts!!.total.annualMinor)
        assertEquals(2, snapshot.normalizedCosts!!.ranked.size)
    }
    @Test fun actualEventBudgetFailureKeepsAllRowsManageable() = runTest {
        val rows = (1L..300L).map { row(it,unit = FrequencyUnit.DAY) }
        val snapshot = forecastSnapshot(rows,today)
        assertEquals(300,snapshot.rows.size)
        assertNull(snapshot.forecast)
        val main = subscriptionStates(flowOf(snapshot.rows),flowOf(snapshot.today)).first() as SubscriptionsUiState.Content
        assertEquals(300,main.active.size); assertEquals(ForecastState.Error,snapshot.state)
    }

    @Test fun oneCollectionRecoversAfterPriceCorrectionWithoutRecreatingFlow() = runTest {
        val rows = MutableStateFlow(listOf(row(price = Long.MAX_VALUE)))
        val updates = Channel<SubscriptionForecastSnapshot>(Channel.UNLIMITED)
        val reader = launch { rows.map { forecastSnapshot(it,today) }.collect { updates.send(it) } }
        try {
            assertNull(updates.receive().forecast)
            rows.value = listOf(row(price = 123))
            val recovered = updates.receive()
            assertEquals(123L,recovered.forecast!!.next30Days.totalMinor)
            assertEquals(1476L,recovered.forecast!!.totalMinor)
        } finally { reader.cancelAndJoin(); updates.close() }
    }
    @Test fun successfulTotalIsWithdrawnOnFailureThenReplacedAutomatically() = runTest {
        val rows = MutableStateFlow(listOf(row()))
        val updates = Channel<SubscriptionForecastSnapshot>(Channel.UNLIMITED)
        val reader = launch { rows.map { forecastSnapshot(it,today) }.collect { updates.send(it) } }
        try {
            assertEquals(1200L,updates.receive().forecast!!.totalMinor)
            rows.value = listOf(row(price = Long.MAX_VALUE))
            val failed = updates.receive()
            assertNull(failed.forecast); assertEquals(ForecastState.Error,failed.state)
            assertEquals(Long.MAX_VALUE,failed.rows.single().priceMinor)
            rows.value = listOf(row(price = 125))
            assertEquals(1500L,updates.receive().forecast!!.totalMinor)
        } finally { reader.cancelAndJoin(); updates.close() }
    }
    @Test fun todayChangeCanRecoverActualBudgetFailureInTheSameCollection() = runTest {
        val rows = (1L..275L).map { row(it,unit = FrequencyUnit.DAY) }
        val dates = MutableStateFlow(today)
        val updates = Channel<SubscriptionForecastSnapshot>(Channel.UNLIMITED)
        val reader = launch { dates.map { forecastSnapshot(rows,it) }.collect { updates.send(it) } }
        try {
            assertNull(updates.receive().forecast) // 275 * 365 > 100 000; production limit unchanged.
            dates.value = today.plusDays(30)
            val recovered = updates.receive()
            assertEquals(today.plusDays(30),recovered.today)
            assertEquals(92125,recovered.forecast!!.charges.size)
            assertEquals(rows,recovered.rows)
        } finally { reader.cancelAndJoin(); updates.close() }
    }
    @Test fun oneProjectionKeepsRowsNormalizationAndCalendarCoherentAcrossUpdates() = runBlocking {
        val original = row()
        val rows = MutableStateFlow(listOf(original))
        val dates = MutableStateFlow(today)
        val updates = Channel<SubscriptionForecastSnapshot>(Channel.UNLIMITED)
        val reader = launch { forecastSnapshots(rows, dates).collect { updates.send(it) } }
        suspend fun next() = withTimeout(5000) { updates.receive() }
        try {
            assertEquals(1200L, next().normalizedCosts!!.total.annualMinor)
            val edited = original.copy(priceMinor = 29950, accountLabel = "Новая подпись", anchorDay = 3)
            rows.value = listOf(edited)
            val updated = next()
            assertEquals(edited, updated.rows.single())
            assertEquals(359400L, updated.normalizedCosts!!.total.annualMinor)
            assertEquals(29950L, updated.normalizedCosts!!.ranked.single().amounts.monthlyMinor)
            assertTrue(updated.forecast!!.charges.all { it.amountMinor == edited.priceMinor })
            dates.value = today.plusDays(3)
            val nextDay = next()
            assertEquals(dates.value, nextDay.today)
            assertEquals(updated.normalization, nextDay.normalization)
            assertNotEquals(updated.forecast!!.charges, nextDay.forecast!!.charges)
            rows.value = listOf(edited.copy(state = SubscriptionState.CANCELLED))
            val inactive = next()
            assertEquals(0L, inactive.normalizedCosts!!.total.annualMinor)
            assertTrue(inactive.normalizedCosts!!.ranked.isEmpty())
            assertEquals(12, inactive.forecast!!.months.size)
            assertTrue(inactive.forecast!!.charges.isEmpty())
        } finally { reader.cancelAndJoin(); updates.close() }
    }

    @Test fun hundredDailyRowsKeepForecastOrderRankingAndMetadataInOneSnapshot() {
        val rows = (1L..100L).map { id ->
            row(id = id, price = 100L + id, unit = FrequencyUnit.DAY).copy(accountLabel = "Метка $id")
        }
        val value = forecastSnapshot(rows.reversed(), today)
        val forecast = requireNotNull(value.forecast)
        val costs = requireNotNull(value.normalizedCosts)
        val byId = value.rows.associateBy { it.id }
        assertEquals(36_500, forecast.charges.size)
        assertEquals(3_100, forecast.next30Days.charges.size)
        assertEquals(5_493_250L, forecast.totalMinor)
        assertEquals(forecast.totalMinor, costs.total.annualMinor)
        assertEquals(rows.map { it.id }, forecast.charges.take(100).map { it.subscriptionId.value })
        assertEquals(rows.asReversed().map { it.id }, costs.ranked.map { it.subscriptionId.value })
        assertEquals("Метка 100", byId.getValue(costs.ranked.first().subscriptionId.value).accountLabel)
        assertTrue(forecast.charges.all { byId.getValue(it.subscriptionId.value).priceMinor == it.amountMinor })
    }

    @Test fun cancellationIsNeverConvertedToFinancialError() {
        assertThrows(CancellationException::class.java) {
            calculationOrNull<String> { throw CancellationException("Synthetic cancellation") }
        }
    }
    @Test fun readFailureDoesNotPublishRowsOrAnEmptyFinancialSuccess() = runTest {
        var published = false
        try {
            subscriptionStates(flow<List<com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity>> {
                throw com.glebskorobogatov.subscriptiontracker.data.room.StorageUnavailable(
                    com.glebskorobogatov.subscriptiontracker.data.room.StorageFailure.CORRUPT)
            },flowOf(today)).collect { published = true }
            fail("Expected storage failure")
        } catch (error: com.glebskorobogatov.subscriptiontracker.data.room.StorageUnavailable) {
            assertEquals(com.glebskorobogatov.subscriptiontracker.data.room.StorageFailure.CORRUPT,error.failure)
        }
        assertFalse(published)
    }
}
