package com.glebskorobogatov.subscriptiontracker.data.integration

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.fixtures.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionUseCasesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: TrackerDatabase
    private lateinit var lease: DatasetSettingsFactory.Lease
    private lateinit var use: SubscriptionUseCases
    private lateinit var dataset: UUID
    private val day = LocalDate.of(2027, 1, 31)
    private val now = Instant.parse("2027-01-31T12:00:00Z")
    private val rub = CurrencyCode.of("RUB")
    private val queries = CopyOnWriteArrayList<String>()
    @Before fun open() = runBlocking {
        dataset = UUID.randomUUID(); com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset); db = TrackerDatabaseFactory.open(context, dataset) { fresh ->
            Room.databaseBuilder(context,TrackerDatabase::class.java,"subscriptions-$dataset.db")
                .openHelperFactory(PreservingOpenHelperFactory(fresh))
                .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> queries += sql },Executor { it.run() }).build()
        }
        lease = DatasetSettingsFactory.acquire(context, dataset)
        use = SubscriptionUseCases(db, lease.repository, CurrentDate(day))
    }
    @After fun close() = runBlocking { db.close(); lease.close(); context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }; Unit }
    private suspend fun create(mode: RenewalMode = RenewalMode.AUTO) = use.createSubscription(SubscriptionDraft(UUID.randomUUID(), "Сервис", "Аккаунт", Money(999, rub), mode,
        RenewalSchedule(RenewalAnchor.from(day), Frequency(FrequencyUnit.MONTH, 1))), now)
    private fun edit(s: SubscriptionEntity, schedule: ScheduleEdit = ScheduleEdit.Keep) = SubscriptionEdit(s.name, s.accountLabel, Money(s.priceMinor, s.currencyCode), s.renewalMode, schedule)
    private suspend fun details(id: UUID) = withTimeout(5000) { use.details(id).first() }!!
    private suspend fun succeeds(block: suspend () -> Unit): Boolean = try { block(); true } catch (_: IllegalArgumentException) { false } catch (_: IllegalStateException) { false }

    @Test fun realRoomFlowsUpdateDetailsWithoutReadingOrObservingFinancialRows() = runBlocking {
        val s = create(); val events = Channel<com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastResult>(Channel.UNLIMITED)
        val collector = launch { use.forecast30Days.collect { events.send(it) } }
        val values = Channel<SubscriptionDetails?>(Channel.UNLIMITED)
        val reader = launch { use.details(s.id).collect { values.send(it) } }
        try {
            val initial = withTimeout(5000) { events.receive() }
            assertEquals(s, withTimeout(5000) { values.receive() }!!.subscription)
            val selectTables = queries.filter { it.trimStart().startsWith("SELECT",true) }.joinToString(" ").lowercase()
            assertTrue(selectTables.contains("from subscriptions"))
            assertTrue(selectTables.contains("reminder_overrides"))
            assertFalse(Regex("from\\s+[`\"]?(payments|refunds)\\b").containsMatchIn(selectTables))
            val p = legacyPayment(db, s, day); val r = legacyRefund(db, p, day, 300)
            assertNull(withTimeoutOrNull(300) { values.receive() })
            assertNull(withTimeoutOrNull(300) { events.receive() })
            val updated = use.editSubscription(s, edit(s).copy(price = Money(1000, CurrencyCode.of("USD"))), now.plusSeconds(1))
            assertNotEquals(initial, withTimeout(5000) { events.receive() })
            assertEquals(updated, withTimeout(5000) { values.receiveAsFlow().first { it?.subscription == updated } }!!.subscription)
            withContext(Dispatchers.IO) { assertEquals(p,db.payments().find(p.id)); assertEquals(r,db.refunds().find(r.id)) }
        } finally { collector.cancelAndJoin(); reader.cancelAndJoin() }
    }
    @Test fun createDuplicatesByNameAndExplicitScheduleChoices() = runBlocking {
        val a = create(); val b = create()
        assertEquals(2, use.subscriptions.first().size)
        val moved = use.editSubscription(a, edit(a, ScheduleEdit.NextOnly(day.minusDays(1))), now)
        use.date.update(day.minusDays(1))
        val dates = use.forecastMonths(3).first().charges.filter { it.subscriptionId.value == a.id }.map { it.dueDate }
        assertEquals(listOf(day.minusDays(1), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31)), dates)
        val series = use.editSubscription(moved, edit(moved, ScheduleEdit.FutureSeries(LocalDate.of(2027, 2, 5), Frequency(FrequencyUnit.MONTH, 3))), now)
        assertEquals(5, series.anchorDay); assertEquals(3, series.intervalCount); assertEquals(b, details(b.id).subscription)
        assertFalse(succeeds { use.editSubscription(a, edit(a), now) })
    }
    @Test fun dateRefreshManualIssueCancellationAndReactivation() = runBlocking {
        val manual = create(RenewalMode.MANUAL); val auto = create()
        assertEquals(2, use.active.first().size)
        use.refreshToday(day.plusDays(1), now)
        assertEquals(listOf(manual.id), use.needsDecision.first().map { it.id.value })
        val issue = auto.copy(lifecycleState = LifecycleState.PAYMENT_ISSUE)
        withContext(Dispatchers.IO) { db.subscriptions().update(issue) }
        assertEquals(2, use.needsDecision.first().size)
        assertTrue(use.forecast30Days.first().charges.isEmpty())
        val resolved = use.changeLifecycle(issue, LifecycleCommand.ResolveWithDate(day.plusDays(2)), now)
        val cancelled = use.changeLifecycle(resolved, LifecycleCommand.DisableRenewal(day.plusDays(2)), now)
        use.refreshToday(day.plusDays(2), now); assertEquals(cancelled, details(auto.id).subscription)
        use.refreshToday(day.plusDays(3), now)
        val archived = details(auto.id).subscription
        assertEquals(LifecycleState.ARCHIVED, archived.lifecycleState)
        val active = use.changeLifecycle(archived, LifecycleCommand.Reactivate(day.plusMonths(1), Frequency(FrequencyUnit.YEAR, 1), RenewalMode.AUTO, Money(100, CurrencyCode.of("JPY"))), now)
        assertEquals("JPY", use.forecastMonths(12).first().availableCurrencies.single().value)
        assertEquals(active, details(auto.id).subscription)
    }
    @Test fun legacyRowsAndSettingsSurviveReopeningWithoutFinancialUseCases() = runBlocking {
        val s = create(); val p = legacyPayment(db,s,day); val r = legacyRefund(db,p,day)
        use.updateSettings { it.copy(theme = Theme.DARK) }
        db.close(); lease.close()
        db = TrackerDatabaseFactory.open(context,dataset); lease = DatasetSettingsFactory.acquire(context,dataset)
        use = SubscriptionUseCases(db,lease.repository,CurrentDate(day))
        assertEquals(s,details(s.id).subscription); assertEquals(Theme.DARK,use.settings.first().theme)
        withContext(Dispatchers.IO) { assertEquals(p,db.payments().find(p.id)); assertEquals(r,db.refunds().find(r.id)) }
    }
    @Test fun confirmedSubscriptionDeletionRetiresOnlyItsExistingOperations() = runBlocking {
        val s = create(); val p = legacyPayment(db,s,day); legacyRefund(db,p,day)
        assertFalse(succeeds { use.deleteSubscription(s,false) })
        assertEquals(s,details(s.id).subscription)
        use.deleteSubscription(s,true)
        assertNull(use.details(s.id).first()); assertFalse(succeeds { use.editSubscription(s,edit(s),now) })
        withContext(Dispatchers.IO) {
            assertTrue(db.retiredPaymentOperations().contains(p.operationId)); assertTrue(db.payments().all().isEmpty()); assertTrue(db.refunds().all().isEmpty())
        }
    }
    @Test fun concurrentDateEditOrArchiveUsesFullExpectedSnapshot() = runBlocking {
        val s = create()
        val results = coroutineScope { listOf(
            async { succeeds { use.editSubscription(s,edit(s,ScheduleEdit.NextOnly(day.plusDays(1))),now.plusSeconds(1)) } },
            async { succeeds { use.changeLifecycle(s,LifecycleCommand.Archive,now.plusSeconds(1)) } }
        ).awaitAll() }
        assertEquals(1,results.count { it })
        withContext(Dispatchers.IO) { assertTrue(db.payments().all().isEmpty()) }
    }
    @Test fun staleSnapshotsCannotDeleteOrArchiveAfterReactivation() = runBlocking {
        val s = create()
        val archived = use.changeLifecycle(s,LifecycleCommand.Archive,now.plusSeconds(1))
        use.changeLifecycle(archived,LifecycleCommand.Reactivate(day.plusMonths(1),Frequency(FrequencyUnit.MONTH,1),RenewalMode.AUTO,Money(999,rub)),now.plusSeconds(2))
        assertFalse(succeeds { use.deleteSubscription(archived,true) })
        assertFalse(succeeds { use.changeLifecycle(s,LifecycleCommand.Archive,now.plusSeconds(3)) })
        withContext(Dispatchers.IO) { assertTrue(db.payments().all().isEmpty()) }
    }
    @Test fun sqlFailureThroughUseCaseRollsBackLifecycleAndRetainsLegacyRows() = runBlocking {
        val s = create(); val p = legacyPayment(db,s,day)
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON subscriptions BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        try { use.changeLifecycle(s,LifecycleCommand.Archive,now); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(s,details(s.id).subscription)
        withContext(Dispatchers.IO) { assertEquals(p,db.payments().find(p.id)) }
    }
    @Test fun fullSnapshotRejectsStalePriceAndSameScheduleAfterReactivation() = runBlocking {
        val s = create()
        val changed = use.editSubscription(s,edit(s).copy(price = Money(2000,rub)),now.plusSeconds(1))
        assertFalse(succeeds { use.changeLifecycle(s,LifecycleCommand.Archive,now.plusSeconds(2)) })
        assertEquals(changed,details(s.id).subscription)
        val archived = use.changeLifecycle(changed,LifecycleCommand.Archive,now.plusSeconds(2))
        use.changeLifecycle(archived,LifecycleCommand.Reactivate(day,Frequency(FrequencyUnit.MONTH,1),RenewalMode.AUTO,Money(999,rub)),now.plusSeconds(3))
        assertFalse(succeeds { use.deleteSubscription(s,true) })
        withContext(Dispatchers.IO) { assertTrue(db.payments().all().isEmpty()) }
    }
    @Test fun settingsFlowUpdatesWithoutOverwritingCustomReminders() = runBlocking {
        val s = create()
        val custom = ReminderOverrideEntity(s.id, ReminderEventType.PAYMENT, ReminderMode.CUSTOM, emptySet())
        val inherited = ReminderOverrideEntity(s.id, ReminderEventType.CANCELLATION, ReminderMode.INHERIT_GLOBAL, emptySet())
        withContext(Dispatchers.IO) { db.reminders().insert(custom); db.reminders().insert(inherited) }
        val values = Channel<Settings>(Channel.UNLIMITED)
        val collector = launch { use.settings.collect { values.send(it) } }
        try {
            withTimeout(5000) { values.receive() }
            use.updateSettings { it.copy(paymentOffsetsDefault = setOf(0), cancellationOffsetsDefault = setOf(7),
                chartHorizonMonths = 6, selectedChartCurrency = rub) }
            val changed = withTimeout(5000) { values.receiveAsFlow().first { it.paymentOffsetsDefault == setOf(0) } }
            assertEquals(setOf(7), changed.cancellationOffsetsDefault)
            assertEquals(emptySet<Int>(), effectiveOffsets(changed, ReminderEventType.PAYMENT, custom))
            assertEquals(setOf(7), effectiveOffsets(changed, ReminderEventType.CANCELLATION, inherited))
            assertEquals(setOf(custom, inherited), details(s.id).reminders.toSet())
            assertEquals(use.forecastMonths(6, rub).first(), use.selectedChart.first())
        } finally { collector.cancelAndJoin() }
    }
    @Test fun editorExtrasAndIndependentRulesCommitTogetherAndConflictOnRules() = runBlocking {
        val row = create()
        val rules = listOf(ReminderOverrideEntity(row.id, ReminderEventType.PAYMENT, ReminderMode.CUSTOM, emptySet()),
            ReminderOverrideEntity(row.id, ReminderEventType.CANCELLATION, ReminderMode.CUSTOM, setOf(7,3)))
        val extra = SubscriptionExtras(day.minusDays(20), day.plusDays(10), 7, rules)
        val updated = use.editSubscription(row, edit(row).copy(extras = extra, expectedReminders = emptyList()), now.plusSeconds(1))
        val detail = details(row.id)
        assertEquals(rules.toSet(),detail.reminders.toSet())
        assertEquals(extra.periodStart,detail.subscription.currentPeriodStartOverride)
        assertEquals(extra.paidAccessEnd,detail.subscription.paidAccessEndDate)
        use.updateSettings { it.copy(paymentOffsetsDefault = setOf(0)) }
        assertEquals(rules.toSet(),details(row.id).reminders.toSet())
        assertFalse(succeeds { use.editSubscription(updated, edit(updated).copy(extras = extra, expectedReminders = emptyList()), now.plusSeconds(2)) })
    }
    @Test fun invalidReminderBatchRollsBackSubscriptionAndUuidIsPreservedOnValidEdit() = runBlocking {
        val row = create()
        val wrong = ReminderOverrideEntity(UUID.randomUUID(),ReminderEventType.PAYMENT,ReminderMode.CUSTOM,setOf(1))
        assertFalse(succeeds { use.editSubscription(row,edit(row).copy(name = "Другой",extras = SubscriptionExtras(null,null,null,listOf(wrong))),now) })
        assertEquals(row,details(row.id).subscription)
        val changed = use.editSubscription(row,edit(row).copy(name = "Другой",price = Money(1234,CurrencyCode.of("KWD"))),now.plusSeconds(1))
        assertEquals(row.id,changed.id)
        assertEquals(1,use.subscriptions.first().size)
    }
    @Test fun editorPriceEditLeavesOtherPlanAndLegacyHistoryUntouched() = runBlocking {
        val a = create(RenewalMode.MANUAL); val b = create(RenewalMode.MANUAL)
        val p = legacyPayment(db,a,day)
        use.editSubscription(a,edit(a).copy(price = Money(1299,rub)),now.plusSeconds(1))
        assertEquals(1299L,details(a.id).subscription.priceMinor); assertEquals(999L,details(b.id).subscription.priceMinor)
        withContext(Dispatchers.IO) { assertEquals(listOf(p),db.payments().all()) }
    }
    @Test fun existingIssueCanResolveByDateWithoutCreatingPaymentOrChangingPrice() = runBlocking {
        val a = create().copy(lifecycleState = LifecycleState.PAYMENT_ISSUE)
        withContext(Dispatchers.IO) { db.subscriptions().update(a) } // Legacy row fixture, not an application command.
        assertTrue(use.forecast30Days.first().charges.isEmpty())
        val resolved = use.changeLifecycle(a,LifecycleCommand.ResolveWithDate(day.plusDays(2)),now)
        assertEquals(a.priceMinor,resolved.priceMinor); assertEquals(LifecycleState.ACTIVE,resolved.lifecycleState)
        assertFalse(use.forecast30Days.first().charges.isEmpty())
        withContext(Dispatchers.IO) { assertTrue(db.payments().all().isEmpty()) }
    }
    @Test fun cancellationLastDayReactivationAndDeletionPreserveIndependentSubscription() = runBlocking {
        val a = create(); val other = create()
        val p = legacyPayment(db,a,day); val refund = legacyRefund(db,p,day)
        use.changeLifecycle(a,LifecycleCommand.DisableRenewal(day.plusDays(3)),now)
        use.refreshToday(day.plusDays(3),now)
        assertEquals(LifecycleState.CANCELLATION_SCHEDULED,details(a.id).subscription.lifecycleState)
        use.refreshToday(day.plusDays(4),now)
        val archived = details(a.id).subscription
        assertEquals(LifecycleState.ARCHIVED,archived.lifecycleState)
        withContext(Dispatchers.IO) { assertEquals(refund,db.refunds().find(refund.id)) }
        val active = use.changeLifecycle(archived,LifecycleCommand.Reactivate(day.plusDays(30),Frequency(FrequencyUnit.MONTH,1),RenewalMode.MANUAL,Money(200,CurrencyCode.of("JPY"))),now)
        assertEquals(a.id,active.id); withContext(Dispatchers.IO) { assertEquals(p,db.payments().find(p.id)) }
        use.deleteSubscription(active,true)
        assertNull(use.details(a.id).first()); assertEquals(other,details(other.id).subscription)
    }
    @Test fun longOverdueManualCanUseExplicitEditorSeriesWithoutPayment() = runBlocking {
        val row = use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Сервис","Давно просрочена",Money(999,rub),RenewalMode.MANUAL,
            RenewalSchedule(RenewalAnchor.from(day.minusMonths(6)),Frequency(FrequencyUnit.MONTH,1))),now)
        val changed = use.editSubscription(row,edit(row,ScheduleEdit.FutureSeries(day.plusMonths(1),Frequency(FrequencyUnit.MONTH,1))),now.plusSeconds(1))
        assertEquals(RenewalAnchor.from(day.plusMonths(1)),changed.toDomain().schedule.anchor)
        assertFalse(succeeds { use.editSubscription(row,edit(row),now.plusSeconds(2)) })
        withContext(Dispatchers.IO) { assertTrue(db.payments().all().isEmpty()) }
    }

    @Test fun detailsRelationNeverCombinesSubscriptionAndRemindersFromDifferentTransactions() = runBlocking {
        val s = create()
        val events = Channel<SubscriptionDetails?>(Channel.UNLIMITED)
        val reader = launch { use.details(s.id).collect { events.send(it) } }
        try {
            withTimeout(5000) { events.receive() }
            withContext(Dispatchers.IO) {
                repeat(12) { index ->
                    db.runInTransaction {
                        db.subscriptions().update(s.copy(name = "revision-$index"))
                        db.reminders().delete(s.id,ReminderEventType.PAYMENT)
                        db.reminders().insert(ReminderOverrideEntity(s.id,ReminderEventType.PAYMENT,ReminderMode.CUSTOM,setOf(listOf(0,1,3,7)[index % 4])))
                    }
                }
            }
            withTimeout(5000) {
                events.receiveAsFlow().first { value ->
                    val detail = requireNotNull(value)
                    val index = detail.subscription.name.removePrefix("revision-").toInt()
                    assertEquals(setOf(listOf(0,1,3,7)[index % 4]),detail.reminders.single { it.eventType == ReminderEventType.PAYMENT }.offsets)
                    index == 11
                }
            }
            use.deleteSubscription(details(s.id).subscription,true)
            withTimeout(5000) { events.receiveAsFlow().first { it == null } }
        } finally { reader.cancelAndJoin(); events.close() }
    }

}
