package com.glebskorobogatov.subscriptiontracker.domain.payment

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PaymentRulesTest {
    private val day = LocalDate.of(2027, 11, 20)
    private val rub = CurrencyCode.of("RUB")
    private val s = Subscription(SubscriptionId.random(), RenewalMode.MANUAL,
        RenewalSchedule(RenewalAnchor.from(day.minusDays(5)), Frequency(FrequencyUnit.MONTH, 1)), Money(49900, rub))
    private fun command() = PaymentCommand(OperationId.random(), s.id, day, Money(59900, rub),
        PaymentSource.CONFIRMED_RENEWAL, expectedSchedule = s.schedule, expectedMode = s.mode, selectedNext = day.plusMonths(1))
    private val rules = PaymentRules()
    @Test fun moneyT05FalseKeepsPriceAndStoresActualAmount() {
        val r = rules.apply(s, command(), day)
        assertEquals(s.price, r.subscription.price); assertEquals(59900L, r.paymentIntent!!.amount.amountMinor)
    }
    @Test fun moneyT06ExplicitTrueChangesFutureOnly() {
        val r = rules.apply(s, command().copy(updateFuturePrice = true), day)
        assertEquals(59900L, r.subscription.price.amountMinor); assertEquals(49900L, s.price.amountMinor)
    }
    @Test fun moneyT07AndT16HistoryRetainsOwnCurrencyAndDoesNotChangeForecast() {
        val c = PaymentCommand(OperationId.random(), s.id, day.minusYears(1), Money(100, CurrencyCode.of("USD")), PaymentSource.MANUAL_HISTORY)
        val result = rules.apply(s, c, day)
        assertEquals(s, result.subscription); assertEquals("USD", result.paymentIntent!!.amount.currency.value)
        val engine = ForecastEngine()
        assertEquals(engine.next30Days(listOf(s), day), engine.next30Days(listOf(result.subscription), day))
    }
    @Test fun moneyT17FuturePaymentRejectedForBothSources() {
        assertThrows(FuturePaymentRejected::class.java) { rules.apply(s, command().copy(paidOn = day.plusDays(1)), day) }
        assertThrows(FuturePaymentRejected::class.java) { rules.apply(s, PaymentCommand(OperationId.random(), s.id, day.plusDays(1), s.price, PaymentSource.MANUAL_HISTORY), day) }
    }
    @Test fun lifeT03T04SuggestionAndExplicitChoiceRemainSeparate() {
        assertEquals(day.plusMonths(1), SubscriptionLifecycleService().suggestNextDate(s, day))
        for (next in listOf(day.plusMonths(1), day.minusDays(5).plusMonths(1))) {
            val r = rules.apply(s, command().copy(selectedNext = next), day)
            assertEquals(day, r.paymentIntent!!.paidOn)
            assertEquals(next, DefaultRenewalCalendar().nextScheduledDate(r.subscription.schedule))
        }
    }
    @Test fun autoAdvancesExactlyOneCycleAndForecastExcludesConfirmedToday() {
        val auto = s.copy(mode = RenewalMode.AUTO, schedule = s.schedule.copy(anchor = RenewalAnchor.from(day)))
        val c = command().copy(expectedMode = auto.mode, expectedSchedule = auto.schedule, selectedNext = null)
        val r = rules.apply(auto, c, day)
        assertEquals(1L, r.subscription.schedule.nextCycleIndex)
        assertEquals(day.plusMonths(1), ForecastEngine().calendarMonths(listOf(r.subscription), day, 3).charges.first().dueDate)
        assertThrows(PaymentConflict::class.java) { rules.apply(r.subscription, c.copy(operationId = OperationId.random()), day) }
    }
    @Test fun currencyStateAndCalendarConflictsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { rules.apply(s, command().copy(amount = Money(1, CurrencyCode.of("USD"))), day) }
        assertThrows(InvalidTransition::class.java) { rules.apply(s.copy(state = LifecycleState.ARCHIVED), command(), day) }
        assertThrows(CycleConflictException::class.java) { rules.apply(s, command().copy(selectedNext = day.plusMonths(3)), day) }
    }
    @Test fun payloadIncludesAllSemanticChoicesButNotOperationId() {
        val c = command()
        assertEquals(c.payload(), c.copy(operationId = OperationId.random()).payload())
        for (changed in listOf(c.copy(updateFuturePrice = true), c.copy(selectedNext = day.plusMonths(1).plusDays(1)),
            c.copy(amount = s.price), c.copy(paidOn = day.minusDays(1)), c.copy(source = PaymentSource.MANUAL_HISTORY),
            c.copy(expectedSchedule = s.schedule.copy(nextCycleIndex = 1)))) assertNotEquals(c.payload(), changed.payload())
    }
}
