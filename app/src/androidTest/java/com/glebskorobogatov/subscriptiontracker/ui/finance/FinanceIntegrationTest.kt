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
    private suspend fun create(label: String,currency: CurrencyCode = rub,mode: RenewalMode = RenewalMode.AUTO): SubscriptionEntity {
        if (currency == rub) return use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Одинаковое имя",label,
            Money(100,rub),mode,RenewalSchedule(RenewalAnchor.from(day),Frequency(FrequencyUnit.MONTH,1))),now)
        // Existing foreign-currency row fixture; never a new application currency choice.
        val row = SubscriptionEntity(UUID.randomUUID(),"Одинаковое имя",label,100,currency,FrequencyUnit.MONTH,1,
            mode,LifecycleState.ACTIVE,day.year,day.monthValue,day.dayOfMonth,0,null,null,null,null,null,null,null,now,now)
        withContext(Dispatchers.IO) { db.subscriptions().insert(row) }; return row
    }
    @Test fun fixedRubTwelveMonthsPreserveLegacyCurrenciesAndSurviveReopeningStores() = runBlocking {
        val a = create("Личный"); val b = create("Семейный",mode = RenewalMode.MANUAL)
        val legacy = create("Старая запись",CurrencyCode.of("USD"))
        val actual = withTimeout(5000) { financeSnapshots(use).first() }
        assertEquals(12,actual.forecast.months.size); assertEquals(rub,actual.currency)
        assertEquals(ForecastEngine().calendarMonths(use.subscriptions.first().map { it.toDomain() },day,12,rub),actual.forecast)
        assertTrue(actual.forecast.charges.all { it.currencyCode == rub && it.dueDate >= day })
        assertEquals(setOf(a.id,b.id),actual.forecast.charges.map { it.subscriptionId.value }.toSet())
        assertEquals(setOf("Личный","Семейный"),actual.rows.filter { it.id in setOf(a.id,b.id) }.map { it.accountLabel }.toSet())
        assertEquals(1,actual.forecast.charges.count { it.subscriptionId.value == b.id })
        db.close(); lease.close(); reopen()
        val restored = withTimeout(5000) { financeSnapshots(use).first() }
        assertEquals(actual,restored); assertEquals(3,restored.rows.size)
        withContext(Dispatchers.IO) { assertEquals(legacy,db.subscriptions().find(legacy.id)) }
    }

    @Test fun liveEditsDateAndLifecycleUpdateForecastWhileHistoryAndRefundsDoNot() = runBlocking {
        val a = create("Личный"); val manual = create("Семейный",mode = RenewalMode.MANUAL)
        val updates = Channel<FinanceSnapshot>(Channel.UNLIMITED)
        val job = launch { financeSnapshots(use).collect { updates.send(it) } }
        suspend fun await(predicate: (FinanceSnapshot) -> Boolean) = withTimeout(5000) { updates.receiveAsFlow().first(predicate) }
        try {
            val first = await { it.forecast.charges.size == 13 }
            val payment = legacyPayment(db,a,day,100)
            legacyRefund(db,payment,day,50)
            assertEquals(first.forecast,financeSnapshots(use).first().forecast)
            val edited = use.editSubscription(a,SubscriptionEdit(a.name,"Обновлённый",Money(200,rub),a.renewalMode),now.plusSeconds(1))
            await { it.forecast.totalsByCurrency[rub]?.amountMinor == 2500L && it.rows.any { r -> r.accountLabel == "Обновлённый" } }
            use.changeLifecycle(edited,LifecycleCommand.Archive,now.plusSeconds(2))
            await { it.forecast.charges.size == 1 && it.forecast.charges.single().subscriptionId.value == manual.id }
            use.date.update(day.plusDays(1))
            await { it.forecast.availableCurrencies.isEmpty() && it.forecast.months.keys.first() == YearMonth.of(2027,2) }
            withContext(Dispatchers.IO) {
                assertEquals(listOf(payment),db.payments().forSubscription(a.id))
                assertEquals(50L,db.refunds().forPayment(payment.id).single().amountMinor)
            }
        } finally { job.cancelAndJoin(); updates.close() }
    }
}
