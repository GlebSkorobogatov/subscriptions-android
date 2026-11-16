package com.glebskorobogatov.subscriptiontracker.domain.integration

import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SubscriptionViewsTest {
    private val day = LocalDate.of(2027, 1, 31)
    private fun sub(mode: RenewalMode = RenewalMode.AUTO) = Subscription(SubscriptionId.random(), mode,
        RenewalSchedule(RenewalAnchor.from(day), Frequency(FrequencyUnit.MONTH, 1)), Money(100, CurrencyCode.of("RUB")))
    @Test fun explicitDayChangesManualClassificationWithoutInventingPaymentIssue() {
        val s = sub(RenewalMode.MANUAL); val source = CurrentDate(day)
        assertEquals(listOf(s), groupSubscriptions(listOf(s), source.today.value).active)
        source.update(day.plusDays(1))
        assertEquals(listOf(s), groupSubscriptions(listOf(s), source.today.value).needsDecision)
        assertEquals(LifecycleState.ACTIVE, s.state)
    }
    @Test fun issueCancelledAndArchiveGroupsAgreeWithForecastExclusions() {
        val issue = sub().copy(state = LifecycleState.PAYMENT_ISSUE)
        val cancelled = sub().copy(state = LifecycleState.CANCELLATION_SCHEDULED)
        val archived = sub().copy(state = LifecycleState.ARCHIVED)
        val result = groupSubscriptions(listOf(issue, cancelled, archived), day)
        assertEquals(listOf(issue), result.needsDecision); assertEquals(listOf(cancelled), result.active)
        assertEquals(listOf(archived), result.archived)
        assertTrue(ForecastEngine().calendarMonths(listOf(issue, cancelled, archived), day, 12).charges.isEmpty())
    }
    @Test fun monthlyProjectionRestores31AndOneOffDoesNotShiftSeries() {
        val s = sub(); val calendar = DefaultRenewalCalendar()
        val next = s.copy(schedule = calendar.moveNextOnly(s.schedule, day.minusDays(1)))
        val result = ForecastEngine().calendarMonths(listOf(next), day.minusDays(1), 3)
        assertEquals(listOf("2027-01-30", "2027-02-28", "2027-03-31"), result.charges.map { it.dueDate.toString() })
        assertEquals(day.dayOfMonth, next.schedule.anchor.day)
    }
    @Test fun yearlyLeapAndReactivatedCurrencyUseOnlyFutureSnapshot() {
        val initial = sub().copy(schedule = RenewalSchedule(RenewalAnchor.from(LocalDate.of(2028, 2, 29)), Frequency(FrequencyUnit.YEAR, 1)))
        assertEquals(LocalDate.of(2032, 2, 29), ForecastEngine().calendarMonths(listOf(initial), LocalDate.of(2032, 1, 1), 3).charges.single().dueDate)
        val active = SubscriptionLifecycleService().reactivate(initial.copy(state = LifecycleState.ARCHIVED), RenewalMode.MANUAL,
            initial.schedule, Money(200, CurrencyCode.of("USD"))).subscription
        assertEquals("USD", ForecastEngine().calendarMonths(listOf(active), LocalDate.of(2028, 1, 1), 3).availableCurrencies.single().value)
    }
}
