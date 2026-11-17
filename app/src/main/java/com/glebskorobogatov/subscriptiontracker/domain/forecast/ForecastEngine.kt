package com.glebskorobogatov.subscriptiontracker.domain.forecast

import com.glebskorobogatov.subscriptiontracker.domain.calendar.DefaultRenewalCalendar
import com.glebskorobogatov.subscriptiontracker.domain.calendar.CalendarCalculationException
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionRecord
import com.glebskorobogatov.subscriptiontracker.domain.model.SubscriptionId
import java.time.LocalDate
import java.time.YearMonth

/** An expectation in RUB kopecks, never a financial fact or persistence command. */
data class ExpectedCharge(val subscriptionId: SubscriptionId, val dueDate: LocalDate, val amountMinor: Long)
data class MonthlyForecast(val month: YearMonth, val charges: List<ExpectedCharge>, val totalMinor: Long)
data class ForecastWindow(val fromInclusive: LocalDate, val untilExclusive: LocalDate,
    val charges: List<ExpectedCharge>, val totalMinor: Long)
data class ForecastResult(val fromInclusive: LocalDate, val untilExclusive: LocalDate,
    val charges: List<ExpectedCharge>, val totalMinor: Long,
    val months: Map<YearMonth, MonthlyForecast>, val next30Days: ForecastWindow)

/** Both windows belong to one input snapshot. Enumerate the 12-month series once; the shorter
 * window is a subset of those same events. No clocks, persisted cycles, currency or horizon selection.
 * Calendar bounds/overflow failures escape before any result can be published. */
class ForecastEngine(private val calendar: DefaultRenewalCalendar = DefaultRenewalCalendar(),
    private val maxEventsPerSnapshot: Int = 100_000) {
    init { require(maxEventsPerSnapshot > 0) }
    fun calculate(subscriptions: List<SubscriptionRecord>, today: LocalDate): ForecastResult {
        val shortEnd = today.plusDays(31)
        val firstMonth = YearMonth.from(today)
        val end = firstMonth.plusMonths(12).atDay(1)
        require(subscriptions.map { it.id }.distinct().size == subscriptions.size) { "Duplicate subscription IDs" }
        var eventCount = 0
        val charges = subscriptions.asSequence().filter { it.participatesInCalculations }.flatMap { subscription ->
            val first = calendar.firstOnOrAfter(subscription.schedule, today)
                ?: throw CalendarCalculationException("No representable next renewal date")
            val events = if (first >= end) emptySequence() else calendar.upcomingEvents(subscription.schedule, first, end)
            events.map { date ->
                // Bound aggregate allocation before sorting; never truncate or limit subscription IDs.
                if (eventCount == maxEventsPerSnapshot) throw CalendarCalculationException("Too many forecast events")
                eventCount++
                ExpectedCharge(subscription.id, date, subscription.price.amountMinor)
            }
        }.sortedWith(compareBy<ExpectedCharge> { it.dueDate }.thenBy { it.subscriptionId.toString() }).toList()
        val grouped = charges.groupBy { YearMonth.from(it.dueDate) }
        val months = (0L until 12L).associate { offset ->
            val month = firstMonth.plusMonths(offset)
            val items = grouped[month].orEmpty()
            month to MonthlyForecast(month, items, total(items))
        }
        val short = charges.filter { it.dueDate < shortEnd }
        return ForecastResult(today, end, charges, total(charges), months,
            ForecastWindow(today, shortEnd, short, total(short)))
    }
    private fun total(charges: List<ExpectedCharge>): Long = charges.fold(0L) { sum, event -> Math.addExact(sum, event.amountMinor) }
}
