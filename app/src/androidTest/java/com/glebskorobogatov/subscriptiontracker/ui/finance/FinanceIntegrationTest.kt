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
        assertEquals(12,actual.forecast.months.size); assertEquals(rub,actual.currency)
        assertEquals(ForecastEngine().calendarMonths(listOf(a,b).map { it.toDomain() },day,12,rub),actual.forecast)
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
            await { it.forecast.totalsByCurrency[rub]?.amountMinor==3600L }
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
}
