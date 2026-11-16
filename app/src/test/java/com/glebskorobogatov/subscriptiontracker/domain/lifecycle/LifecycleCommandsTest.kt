package com.glebskorobogatov.subscriptiontracker.domain.lifecycle

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LifecycleCommandsTest {
    private val today = LocalDate.of(2027, 11, 10)
    private val frequency = Frequency(FrequencyUnit.MONTH, 1)
    private val s = Subscription(SubscriptionId.random(), RenewalMode.AUTO,
        RenewalSchedule(RenewalAnchor.from(today), frequency), Money(100, CurrencyCode.of("RUB")))
    private val rules = LifecycleCommands()
    @Test fun existingIssueIsExcludedFromForecastWithoutCreatingAnotherIssue() {
        val issue = s.copy(state = LifecycleState.PAYMENT_ISSUE)
        assertTrue(ForecastEngine().next30Days(listOf(issue), today).charges.isEmpty())
    }
    @Test fun lifeT08T09DateOrArchiveResolvesIssueWithoutPaymentCommand() {
        val issue = s.copy(state = LifecycleState.PAYMENT_ISSUE)
        val moved = rules.apply(issue, LifecycleCommand.ResolveWithDate(today.plusDays(2)))
        assertEquals(LifecycleState.ACTIVE, moved.state)
        assertEquals(today.plusDays(2), DefaultRenewalCalendar().nextScheduledDate(moved.schedule))
        assertEquals(s.schedule.anchor, moved.schedule.anchor)
        assertEquals(LifecycleState.ARCHIVED, rules.apply(issue, LifecycleCommand.Archive).state)
    }
    @Test fun lifeT10T11CancellationPreservesKnownEndAndNeverInventsUnknown() {
        val known = rules.apply(s.copy(paidAccessEnd = today), LifecycleCommand.DisableRenewal())
        assertEquals(today, known.paidAccessEnd)
        assertTrue(ForecastEngine().calendarMonths(listOf(known), today, 12).charges.isEmpty())
        val unknown = rules.apply(s, LifecycleCommand.DisableRenewal())
        assertNull(unknown.paidAccessEnd); assertEquals(unknown, autoArchiveExpired(unknown, today.plusYears(10)))
    }
    @Test fun lifeT12InclusiveLastDayAndIdempotentArchive() {
        val cancelled = rules.apply(s, LifecycleCommand.DisableRenewal(today))
        assertEquals(cancelled, autoArchiveExpired(cancelled, today.minusDays(1)))
        assertEquals(cancelled, autoArchiveExpired(cancelled, today))
        val archived = autoArchiveExpired(cancelled, today.plusDays(1))
        assertEquals(LifecycleState.ARCHIVED, archived.state)
        assertEquals(archived, autoArchiveExpired(archived, today.plusDays(2)))
    }
    @Test fun autoArchiveDoesNotTouchOtherStatesOrManualOverdue() {
        for (state in listOf(LifecycleState.ACTIVE, LifecycleState.PAYMENT_ISSUE, LifecycleState.ARCHIVED)) {
            val candidate = s.copy(state = state, paidAccessEnd = today.minusDays(1))
            assertEquals(candidate, autoArchiveExpired(candidate, today))
        }
        val overdue = s.copy(mode = RenewalMode.MANUAL)
        assertEquals(overdue, autoArchiveExpired(overdue, today.plusDays(1)))
    }
    @Test fun lifeT13ReactivationUsesExplicitNewPriceModeAndDate() {
        val archived = rules.apply(s, LifecycleCommand.Archive)
        val command = LifecycleCommand.Reactivate(today.plusDays(10), Frequency(FrequencyUnit.WEEK, 2), RenewalMode.MANUAL, Money(900, CurrencyCode.of("USD")))
        val active = rules.apply(archived, command)
        assertEquals(LifecycleState.ACTIVE, active.state); assertEquals(command.price, active.price)
        assertEquals(command.nextDate, DefaultRenewalCalendar().nextScheduledDate(active.schedule))
        assertThrows(InvalidTransition::class.java) { rules.apply(s, command) }
    }
}
