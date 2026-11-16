package com.glebskorobogatov.subscriptiontracker.data.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.payment.toDomain
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.payment.*
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
    @Before fun open() = runBlocking {
        dataset = UUID.randomUUID(); db = TrackerDatabaseFactory.open(context, dataset)
        lease = DatasetSettingsFactory.acquire(context, dataset)
        use = SubscriptionUseCases(db, lease.repository, CurrentDate(day))
    }
    @After fun close() = runBlocking { db.close(); lease.close(); context.deleteDatabase("subscriptions-$dataset.db"); Unit }
    private suspend fun create(mode: RenewalMode = RenewalMode.AUTO) = use.createSubscription(SubscriptionDraft(UUID.randomUUID(), "Сервис", "Аккаунт", Money(999, rub), mode,
        RenewalSchedule(RenewalAnchor.from(day), Frequency(FrequencyUnit.MONTH, 1))), now)
    private fun edit(s: SubscriptionEntity, schedule: ScheduleEdit = ScheduleEdit.Keep) = SubscriptionEdit(s.name, s.accountLabel, Money(s.priceMinor, s.currencyCode), s.renewalMode, schedule)
    private fun command(s: SubscriptionEntity) = PaymentCommand(OperationId.random(), SubscriptionId(s.id), day, Money(999, rub), PaymentSource.CONFIRMED_RENEWAL,
        expectedSchedule = s.toDomain().schedule, expectedMode = s.renewalMode)
    private suspend fun details(id: UUID) = withTimeout(5000) { use.details(id).first() }!!
    private suspend fun succeeds(block: suspend () -> Unit): Boolean = try { block(); true } catch (_: IllegalArgumentException) { false } catch (_: IllegalStateException) { false }

    @Test fun realRoomFlowsUpdateDetailsButHistoryDoesNotEmitForecast() = runBlocking {
        val s = create(); val events = Channel<com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastResult>(Channel.UNLIMITED)
        val collector = launch { use.forecast30Days.collect { events.send(it) } }
        val histories = Channel<SubscriptionDetails?>(Channel.UNLIMITED)
        val historyCollector = launch { use.details(s.id).collect { histories.send(it) } }
        try {
            val initial = withTimeout(5000) { events.receive() }
            assertTrue(withTimeout(5000) { histories.receive() }!!.history.isEmpty())
            val p = use.addHistoricalPayment(OperationId.random(), SubscriptionId(s.id), day, Money(999, rub), now)
            val r = use.addRefund(RefundEntity(UUID.randomUUID(), p.id, day, 300, rub))
            val observed = withTimeout(5000) { histories.receiveAsFlow().first { it?.history?.singleOrNull()?.netExpense?.amountMinor == 699L } }!!
            assertEquals(s, observed.subscription)
            use.editRefund(r, day, Money(200, rub)); use.editPayment(p, day, Money(900, rub))
            assertEquals(700L, details(s.id).history.single().netExpense.amountMinor)
            assertNull(withTimeoutOrNull(300) { events.receive() })
            val updated = use.editSubscription(s, edit(s).copy(price = Money(1000, CurrencyCode.of("USD"))), now.plusSeconds(1))
            val changed = withTimeout(5000) { events.receive() }
            assertNotEquals(initial, changed); assertEquals("USD", changed.availableCurrencies.single().value)
            assertEquals("RUB", details(s.id).history.single().payment.currencyCode.value)
            assertEquals(updated, details(s.id).subscription)
        } finally { collector.cancelAndJoin(); historyCollector.cancelAndJoin() }
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
        val issue = use.changeLifecycle(auto, LifecycleCommand.MarkPaymentIssue, now)
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
    @Test fun allPaymentAndRefundUseCasesPreserveRetiredKeysAcrossReopen() = runBlocking {
        val s = create(); val c = command(s)
        val p = use.confirmPayment(s, c, now)
        val refund = use.addRefund(RefundEntity(UUID.randomUUID(), p.id, day, 100, rub))
        use.deleteRefund(refund)
        assertFalse(succeeds { use.addRefund(refund) })
        use.deletePayment(p, true)
        assertFalse(succeeds { use.confirmPayment(s, c, now) })
        val retained = use.addHistoricalPayment(OperationId.random(), SubscriptionId(s.id), day, Money(500, rub), now)
        val retainedRefund = use.addRefund(RefundEntity(UUID.randomUUID(), retained.id, day, 200, rub))
        use.updateSettings { it.copy(theme = Theme.DARK) }
        db.close(); lease.close()
        db = TrackerDatabaseFactory.open(context, dataset); lease = DatasetSettingsFactory.acquire(context, dataset)
        use = SubscriptionUseCases(db, lease.repository, CurrentDate(day))
        assertEquals(Theme.DARK, use.settings.first().theme)
        assertFalse(succeeds { use.confirmPayment(s, c, now) }); assertFalse(succeeds { use.addRefund(refund) })
        val history = details(s.id).history.single()
        assertEquals(retained, history.payment); assertEquals(listOf(retainedRefund), history.refunds)
        assertEquals(300L, history.netExpense.amountMinor); assertEquals(1L, details(s.id).subscription.nextCycleIndex)
    }
    @Test fun issuePaymentAndSubscriptionDeletionCannotBypassReceipts() = runBlocking {
        val s = create(); val issue = use.changeLifecycle(s, LifecycleCommand.MarkPaymentIssue, now)
        val c = command(issue).copy(expectedState = LifecycleState.PAYMENT_ISSUE, selectedNext = day.plusMonths(1))
        val p = use.resolvePaymentIssue(issue, c, now)
        assertEquals(p, use.resolvePaymentIssue(issue, c, now))
        assertFalse(succeeds { use.deleteSubscription(details(s.id).subscription, false) })
        val current = details(s.id).subscription; use.deleteSubscription(current, true)
        assertNull(use.details(s.id).first()); assertFalse(succeeds { use.editSubscription(current, edit(current), now) })
        assertFalse(succeeds { use.resolvePaymentIssue(issue, c, now) })
    }
    @Test fun concurrentConfirmationWithDateEditOrArchiveIsControlled() = runBlocking {
        for (archive in listOf(false, true)) {
            val s = create(); val c = command(s)
            val results = coroutineScope {
                listOf(async { succeeds { use.confirmPayment(s, c, now) } }, async { succeeds {
                    if (archive) use.changeLifecycle(s, LifecycleCommand.Archive, now)
                    else use.editSubscription(s, edit(s, ScheduleEdit.NextOnly(day.plusDays(1))), now)
                } }).awaitAll()
            }
            assertEquals(1, results.count { it })
            assertTrue(details(s.id).history.size <= 1)
        }
    }
    @Test fun concurrentPaymentEditAndRefundOrTwoRefundsPreserveLimit() = runBlocking {
        for (editPayment in listOf(true, false)) {
            val s = create(); val p = use.addHistoricalPayment(OperationId.random(), SubscriptionId(s.id), day, Money(999, rub), now)
            val results = coroutineScope { listOf(
                async { succeeds { use.addRefund(RefundEntity(UUID.randomUUID(), p.id, day, 600, rub)) } },
                async { succeeds { if (editPayment) use.editPayment(p, day, Money(500, rub)) else use.addRefund(RefundEntity(UUID.randomUUID(), p.id, day, 600, rub)) } }
            ).awaitAll() }
            assertEquals(1, results.count { it }); assertTrue(details(s.id).history.single().netExpense.amountMinor >= 0)
        }
    }
    @Test fun competingDeleteAndReplayOrStaleReactivationNeverCreateSecondCycle() = runBlocking {
        val s = create(); val c = command(s); val p = use.confirmPayment(s, c, now)
        coroutineScope { listOf(async { use.deletePayment(p, true) }, async { succeeds { use.confirmPayment(s, c, now) } }).awaitAll() }
        assertTrue(details(s.id).history.isEmpty()); assertEquals(1L, details(s.id).subscription.nextCycleIndex)
        val archived = use.changeLifecycle(details(s.id).subscription, LifecycleCommand.Archive, now)
        use.changeLifecycle(archived, LifecycleCommand.Reactivate(day.plusMonths(1), Frequency(FrequencyUnit.MONTH, 1), RenewalMode.AUTO, Money(999, rub)), now)
        assertFalse(succeeds { use.deleteSubscription(archived, true) })
        assertFalse(succeeds { use.changeLifecycle(s, LifecycleCommand.Archive, now) })
    }
    @Test fun sqlFailureThroughUseCaseRollsBackPaymentAndLifecycle() = runBlocking {
        val s = create()
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON subscriptions BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        try { use.confirmPayment(s, command(s), now); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        val state = details(s.id); assertEquals(s, state.subscription); assertTrue(state.history.isEmpty())
    }
    @Test fun concurrentDuplicateConfirmationReplaysReceiptAndAdvancesOnlyOnce() = runBlocking {
        val s = create(); val c = command(s)
        val receipts = coroutineScope { List(2) { async { use.confirmPayment(s, c, now) } }.awaitAll() }
        assertEquals(receipts[0], receipts[1])
        assertEquals(1, details(s.id).history.size)
        assertEquals(1L, details(s.id).subscription.nextCycleIndex)
        assertFalse(succeeds { use.confirmPayment(s, c.copy(amount = Money(998, rub)), now) })
    }
    @Test fun fullSnapshotRejectsStalePriceAndSameScheduleAfterReactivation() = runBlocking {
        val s = create(); val c = command(s).copy(updateFuturePrice = true)
        val changed = use.editSubscription(s, edit(s).copy(price = Money(2000, rub)), now.plusSeconds(1))
        assertFalse(succeeds { use.confirmPayment(s, c, now) })
        assertTrue(details(s.id).history.isEmpty()); assertEquals(changed, details(s.id).subscription)
        val archived = use.changeLifecycle(changed, LifecycleCommand.Archive, now.plusSeconds(2))
        use.changeLifecycle(archived, LifecycleCommand.Reactivate(day, Frequency(FrequencyUnit.MONTH, 1),
            RenewalMode.AUTO, Money(999, rub)), now.plusSeconds(3))
        // Calendar, state and price match the old command, but its complete snapshot is stale.
        assertFalse(succeeds { use.confirmPayment(s, c, now) })
        assertTrue(details(s.id).history.isEmpty())
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
    @Test fun restoredPaymentAttemptReplaysOneReceiptAfterDatabaseReopen() = runBlocking {
        val row = create(RenewalMode.MANUAL)
        val saved = com.glebskorobogatov.subscriptiontracker.ui.details.PaymentAttempt.snapshot(row)
        val fields = mapOf("paid" to day.toString(),"amount" to "12,99","currency" to "RUB","next" to day.plusMonths(1).toString(),"priceChoice" to "keep")
        val operation = UUID.randomUUID().toString()
        val first = com.glebskorobogatov.subscriptiontracker.ui.details.PaymentAttempt.command(row,fields,operation,day)
        val receipt = use.confirmPayment(row,first,now)
        db.close(); lease.close()
        db = TrackerDatabaseFactory.open(context,dataset)
        lease = DatasetSettingsFactory.acquire(context,dataset)
        use = SubscriptionUseCases(db,lease.repository,CurrentDate(day))
        val restored = com.glebskorobogatov.subscriptiontracker.ui.details.PaymentAttempt.row(saved)
        val repeated = com.glebskorobogatov.subscriptiontracker.ui.details.PaymentAttempt.command(restored,fields,operation,day)
        assertEquals(receipt,use.confirmPayment(restored,repeated,now.plusSeconds(1)))
        assertEquals(1,details(row.id).history.size)
        assertEquals(999L,details(row.id).subscription.priceMinor)
    }
    @Test fun explicitPriceChoicesAffectOnlySelectedFuturePrice() = runBlocking {
        val a = create(RenewalMode.MANUAL); val b = create(RenewalMode.MANUAL)
        use.confirmPayment(a,command(a).copy(amount = Money(1299,rub),selectedNext = day.plusMonths(1)),now)
        use.confirmPayment(b,command(b).copy(amount = Money(1299,rub),selectedNext = day.plusMonths(1),updateFuturePrice = true),now)
        assertEquals(999L,details(a.id).subscription.priceMinor)
        assertEquals(1299L,details(b.id).subscription.priceMinor)
        assertEquals(1299L,details(a.id).history.single().payment.amountMinor)
        assertEquals(1299L,details(b.id).history.single().payment.amountMinor)
    }
    @Test fun issueDateAndIssuePaymentHaveDistinctFinancialEffects() = runBlocking {
        val a = create()
        val issue = use.changeLifecycle(a,LifecycleCommand.MarkPaymentIssue,now)
        assertTrue(details(a.id).history.isEmpty()); assertTrue(use.forecast30Days.first().charges.isEmpty())
        val resolved = use.changeLifecycle(issue,LifecycleCommand.ResolveWithDate(day.plusDays(2)),now)
        assertTrue(details(a.id).history.isEmpty()); assertFalse(use.forecast30Days.first().charges.isEmpty())
        val again = use.changeLifecycle(resolved,LifecycleCommand.MarkPaymentIssue,now)
        use.resolvePaymentIssue(again,command(again).copy(expectedState = LifecycleState.PAYMENT_ISSUE,selectedNext = day.plusMonths(1)),now)
        assertEquals(LifecycleState.ACTIVE,details(a.id).subscription.lifecycleState)
        assertEquals(1,details(a.id).history.size)
    }
    @Test fun cancellationLastDayReactivationAndDeletionPreserveIndependentSubscription() = runBlocking {
        val a = create(); val other = create()
        val p = use.addHistoricalPayment(OperationId.random(),SubscriptionId(a.id),day,Money(999,rub),now)
        use.addRefund(RefundEntity(UUID.randomUUID(),p.id,day,100,rub))
        use.changeLifecycle(a,LifecycleCommand.DisableRenewal(day.plusDays(3)),now)
        use.refreshToday(day.plusDays(3),now)
        assertEquals(LifecycleState.CANCELLATION_SCHEDULED,details(a.id).subscription.lifecycleState)
        use.refreshToday(day.plusDays(4),now)
        val archived = details(a.id).subscription
        assertEquals(LifecycleState.ARCHIVED,archived.lifecycleState)
        assertEquals(1,details(a.id).history.single().refunds.size)
        val active = use.changeLifecycle(archived,LifecycleCommand.Reactivate(day.plusDays(30),Frequency(FrequencyUnit.MONTH,1),RenewalMode.MANUAL,Money(200,CurrencyCode.of("JPY"))),now)
        assertEquals(a.id,active.id); assertEquals(1,details(a.id).history.size)
        use.deleteSubscription(active,true)
        assertNull(use.details(a.id).first()); assertEquals(other,details(other.id).subscription)
    }
    @Test fun longOverdueExplicitSeriesHasOneReceiptAndGuardedPayload() = runBlocking {
        val row = use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Сервис","Давно просрочена",Money(999,rub),RenewalMode.MANUAL,
            RenewalSchedule(RenewalAnchor.from(day.minusMonths(6)),Frequency(FrequencyUnit.MONTH,1))),now)
        val command = command(row).copy(selectedNext = day.plusMonths(1),reanchor = true)
        val receipt = use.confirmPayment(row,command,now)
        assertEquals(receipt,use.confirmPayment(row,command,now.plusSeconds(1)))
        assertFalse(succeeds { use.confirmPayment(row,command.copy(reanchor = false),now) })
        assertEquals(1,details(row.id).history.size)
        assertEquals(RenewalAnchor.from(day.plusMonths(1)),use.active.first().single().schedule.anchor)
    }
}
