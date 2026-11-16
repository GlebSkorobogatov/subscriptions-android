package com.glebskorobogatov.subscriptiontracker.domain.lifecycle

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LifecycleTest {
    private val service = SubscriptionLifecycleService()
    private fun d(text: String) = LocalDate.parse(text)
    private val today = d("2027-11-20")
    private val price = Money.parse("499", CurrencyCode.of("RUB"))
    private fun subscription(mode: RenewalMode = RenewalMode.MANUAL) = Subscription(
        SubscriptionId.random(), mode, RenewalSchedule(RenewalAnchor.from(d("2027-11-15")),
            Frequency(FrequencyUnit.MONTH, 1)), price)

    @Test fun lifeT01AutoAdvancesExpectedDateWithoutPaymentOrMutation() {
        val s = subscription(RenewalMode.AUTO)
        val history = listOf(service.addHistoricalPayment(s, OperationId.random(), d("2027-10-15"), price).paymentIntent)
        assertEquals(d("2027-12-15"), service.view(s, today).expectedDate)
        assertEquals(LifecycleState.ACTIVE, s.state)
        assertEquals(0L, s.schedule.nextCycleIndex)
        assertEquals(d("2027-10-15"), history.single()!!.paidOn)
    }
    @Test fun lifeT02ManualTomorrowTodayYesterday() {
        val s = subscription()
        for (day in listOf("2027-11-14", "2027-11-15")) {
            assertEquals(VisibleStatus.ACTIVE, service.view(s, d(day)).status)
            assertEquals(d("2027-11-15"), service.view(s, d(day)).expectedDate)
        }
        assertEquals(VisibleStatus.AWAITING_RENEWAL, service.view(s, today).status)
        assertNull(service.view(s, today).expectedDate)
    }
    @Test fun lifeT03LatePaymentSuggestsActualDatePlusInterval() {
        val s = subscription()
        assertEquals(d("2027-12-20"), service.suggestNextDate(s, today))
        val result = service.confirmRenewal(s, OperationId.random(), today, price, d("2027-12-20"))
        assertEquals(today, result.paymentIntent!!.paidOn)
        assertEquals(d("2027-12-20"), service.view(result.subscription, today).expectedDate)
        assertEquals(s.schedule.anchor, result.subscription.schedule.anchor)
    }
    @Test fun lifeT04SelectedNextDateIsIndependentOfActualPayment() {
        val result = service.confirmRenewal(subscription(), OperationId.random(), today, price, d("2027-12-15"))
        assertEquals(today, result.paymentIntent!!.paidOn)
        assertEquals(d("2027-12-15"), service.view(result.subscription, today).expectedDate)
    }
    @Test fun lifeT05HistoricalPaymentDoesNotResolveOverdue() {
        val s = subscription()
        val result = service.addHistoricalPayment(s, OperationId.random(), d("2027-11-15"), price)
        assertEquals(s, result.subscription)
        assertEquals(PaymentSource.MANUAL_HISTORY, result.paymentIntent!!.source)
        assertEquals(VisibleStatus.AWAITING_RENEWAL, service.view(result.subscription, today).status)
    }
    @Test fun issueIsExplicitAndResolvedByDateWithoutPayment() {
        val issue = service.markPaymentIssue(subscription(RenewalMode.AUTO)).subscription
        assertNull(service.view(issue, today).expectedDate)
        val result = service.resolveWithDate(issue, d("2027-11-25"))
        assertNull(result.paymentIntent)
        assertEquals(LifecycleState.ACTIVE, result.subscription.state)
        assertEquals(d("2027-11-25"), service.view(result.subscription, today).expectedDate)
    }
    @Test fun issueCanResolveWithPaymentIntent() {
        val issue = service.markPaymentIssue(subscription(RenewalMode.AUTO)).subscription
        val result = service.resolveWithPayment(issue, OperationId.random(), today, price, d("2027-12-15"))
        assertEquals(LifecycleState.ACTIVE, result.subscription.state)
        assertEquals(PaymentSource.CONFIRMED_RENEWAL, result.paymentIntent!!.source)
    }
    @Test fun cancellationKeepsKnownOrUnknownAccessAndNeverAutoArchives() {
        for (end in listOf(null, d("2027-11-21"))) {
            val cancelled = service.cancelRenewal(subscription(), end).subscription
            assertEquals(end, cancelled.paidAccessEnd)
            for (day in listOf(today, d("2027-11-21"), d("2028-01-01"))) {
                assertEquals(VisibleStatus.CANCELLATION_SCHEDULED, service.view(cancelled, day).status)
                assertNull(service.view(cancelled, day).expectedDate)
            }
        }
    }
    @Test fun archiveAndReactivateAreExplicit() {
        val s = subscription()
        val archived = service.archive(s).subscription
        assertNull(service.view(archived, today).expectedDate)
        val restored = service.reactivate(archived, RenewalMode.AUTO, s.schedule, price).subscription
        assertEquals(s.id, restored.id)
        assertEquals(LifecycleState.ACTIVE, restored.state)
    }
    @Test fun invalidTransitionsFail() {
        val s = subscription()
        assertThrows(InvalidTransition::class.java) { service.markPaymentIssue(s) }
        assertThrows(InvalidTransition::class.java) { service.resolveWithDate(s, today) }
        assertThrows(InvalidTransition::class.java) { service.reactivate(s, s.mode, s.schedule, price) }
        val archived = service.archive(s).subscription
        assertThrows(InvalidTransition::class.java) { service.archive(archived) }
        assertThrows(InvalidTransition::class.java) { service.cancelRenewal(archived) }
        assertThrows(InvalidTransition::class.java) { service.confirmRenewal(archived, OperationId.random(), today, price, today.plusMonths(1)) }
    }
    @Test fun calT14AutoDueIsInclusive() {
        val s = subscription(RenewalMode.AUTO)
        assertEquals(d("2027-11-15"), service.view(s, d("2027-11-15")).expectedDate)
        assertEquals(d("2027-12-15"), service.view(s, d("2027-11-16")).expectedDate)
    }
    @Test fun calT18ProgressIsClampedAndOverdueManualIsEmpty() {
        val s = subscription().copy(periodStartOverride = d("2027-11-05"))
        assertEquals(1.0, service.view(s, d("2027-11-01")).remainingFraction!!, 0.0)
        assertEquals(0.5, service.view(s, d("2027-11-10")).remainingFraction!!, 0.0)
        assertEquals(0.0, service.view(s, d("2027-11-15")).remainingFraction!!, 0.0)
        assertEquals(VisibleStatus.ACTIVE, service.view(s, d("2027-11-15")).status)
        assertEquals(VisibleStatus.AWAITING_RENEWAL, service.view(s, today).status)
        assertNull(service.view(s, today).remainingFraction)
    }
    @Test fun calT19StartOverrideChangesOnlyProgress() {
        val s = subscription(RenewalMode.AUTO)
        val changed = s.copy(periodStartOverride = today)
        assertEquals(s.schedule, changed.schedule)
        assertEquals(service.view(s, today).expectedDate, service.view(changed, today).expectedDate)
        assertEquals(1.0, service.view(changed, today).remainingFraction!!, 0.0)
        assertTrue(service.view(s, today).remainingFraction!! < 1.0)
    }
    @Test fun cancellationProgressUsesExplicitStartAndClampsAtBoundaries() {
        val s = service.cancelRenewal(subscription().copy(periodStartOverride = d("2027-11-05")),
            d("2027-11-15")).subscription
        for ((day, fraction) in listOf("2027-11-01" to 1.0, "2027-11-05" to 1.0,
            "2027-11-10" to 0.5, "2027-11-15" to 0.0, "2027-12-01" to 0.0)) {
            val view = service.view(s, d(day))
            assertEquals(fraction, view.remainingFraction!!, 0.0)
            assertNull(view.expectedDate)
            assertEquals(VisibleStatus.CANCELLATION_SCHEDULED, view.status)
        }
    }
    @Test fun cancellationInfersPriorCalendarBoundaryIncludingOneOff() {
        val calendar = DefaultRenewalCalendar()
        val base = RenewalSchedule(RenewalAnchor.from(d("2027-01-31")),
            Frequency(FrequencyUnit.MONTH, 1), nextCycleIndex = 1)
        for (schedule in listOf(base, calendar.moveNextOnly(base, d("2027-02-26")))) {
            val s = service.cancelRenewal(subscription(RenewalMode.AUTO).copy(schedule = schedule),
                d("2027-03-02")).subscription
            // 30-day interval: Jan 31 to Mar 2, regardless of shortened/moved due date.
            assertEquals(1.0, service.view(s, d("2027-01-31")).remainingFraction!!, 0.0)
            assertEquals(0.5, service.view(s, d("2027-02-15")).remainingFraction!!, 0.0)
            assertEquals(0.0, service.view(s, d("2027-03-02")).remainingFraction!!, 0.0)
            assertEquals(0.0, service.view(s, d("2027-04-01")).remainingFraction!!, 0.0)
            assertEquals(schedule, s.schedule)
        }
    }
    @Test fun cancellationWithoutPriorBoundaryDoesNotInventStart() {
        val s = service.cancelRenewal(subscription(), d("2027-11-30")).subscription
        assertNull(service.view(s, today).remainingFraction)
    }
    @Test fun cancellationWithoutEndHasNoProgressEvenWithKnownStart() {
        val s = service.cancelRenewal(subscription().copy(periodStartOverride = d("2027-11-01")), null).subscription
        assertNull(service.view(s, today).remainingFraction)
        assertNull(s.paidAccessEnd)
    }
    @Test fun cancellationRejectsNonpositiveProgressIntervals() {
        for (start in listOf("2027-11-30", "2027-12-01")) {
            val s = service.cancelRenewal(subscription().copy(periodStartOverride = d(start)), d("2027-11-30")).subscription
            assertNull(service.view(s, today).remainingFraction)
        }
    }

}
