package com.glebskorobogatov.subscriptiontracker.domain.forecast

import com.glebskorobogatov.subscriptiontracker.domain.calendar.DefaultRenewalCalendar
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode
import com.glebskorobogatov.subscriptiontracker.domain.model.Money
import com.glebskorobogatov.subscriptiontracker.domain.model.SubscriptionId
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** An expectation, never a confirmed payment or persistence command. */
data class ExpectedCharge(
    val subscriptionId: SubscriptionId,
    val dueDate: LocalDate,
    val amountMinor: Long,
    val currencyCode: CurrencyCode,
)

data class MonthlyForecast(
    val charges: List<ExpectedCharge>,
    val totalsByCurrency: Map<CurrencyCode, Money>,
)

data class ForecastResult(
    val fromInclusive: LocalDate,
    val untilExclusive: LocalDate,
    val charges: List<ExpectedCharge>,
    val totalsByCurrency: Map<CurrencyCode, Money>,
    val months: Map<YearMonth, MonthlyForecast>,
    /** Currencies with eligible events in this window, before the optional currency filter. */
    val availableCurrencies: List<CurrencyCode>,
)

/** Pure recalculation from a snapshot. All dates are explicit; history is deliberately not an input. */
class ForecastEngine(private val calendar: DefaultRenewalCalendar = DefaultRenewalCalendar()) {
    fun next30Days(subscriptions: List<SubscriptionRecord>, today: LocalDate, currency: CurrencyCode? = null): ForecastResult =
        calculate(subscriptions, today, today.plusDays(30), currency)

    fun calendarMonths(
        subscriptions: List<SubscriptionRecord>, today: LocalDate, months: Int, currency: CurrencyCode? = null,
    ): ForecastResult {
        require(months in setOf(3, 6, 12)) { "Expected 3, 6 or 12 calendar months" }
        return calculate(subscriptions, today, YearMonth.from(today).plusMonths(months.toLong()).atDay(1), currency)
    }

    /** Half-open future window. Callers supply today (or a later date) as its lower boundary. */
    fun calculate(
        subscriptions: List<SubscriptionRecord>, fromInclusive: LocalDate, untilExclusive: LocalDate,
        currency: CurrencyCode? = null,
    ): ForecastResult {
        require(fromInclusive <= untilExclusive) { "Range end must not precede start" }
        if (fromInclusive < untilExclusive) {
            require(ChronoUnit.MONTHS.between(YearMonth.from(fromInclusive),
                YearMonth.from(untilExclusive.minusDays(1))) < 10_000) {
                "Too many month buckets; narrow the query range"
            }
        }
        require(subscriptions.map { it.id }.distinct().size == subscriptions.size) { "Duplicate subscription IDs" }
        val events = mutableListOf<ExpectedCharge>()
        if (fromInclusive < untilExclusive) subscriptions.forEach { subscription ->
            if (!subscription.participatesInCalculations) return@forEach
            calendar.upcomingEvents(subscription.schedule, fromInclusive, untilExclusive).forEach { date ->
                events += ExpectedCharge(subscription.id, date, subscription.price.amountMinor, CurrencyCode.of("RUB"))
            }
        }
        val available = events.map { it.currencyCode }.distinct().sortedBy { it.value }
        val charges = events.filter { currency == null || it.currencyCode == currency }.sortedWith(
            compareBy<ExpectedCharge> { it.dueDate }.thenBy { it.subscriptionId.toString() })
        val grouped = charges.groupBy { YearMonth.from(it.dueDate) }
        val months = linkedMapOf<YearMonth, MonthlyForecast>()
        // Include empty calendar buckets so a future chart can display zero-expense months.
        if (fromInclusive < untilExclusive) {
            var month = YearMonth.from(fromInclusive)
            val last = YearMonth.from(untilExclusive.minusDays(1))
            while (month <= last) {
                val items = grouped[month].orEmpty()
                months[month] = MonthlyForecast(items, totals(items))
                if (month == last) break
                month = month.plusMonths(1)
            }
        }
        return ForecastResult(fromInclusive, untilExclusive, charges, totals(charges), months, available)
    }

    /** Money.plus uses Math.addExact: overflow fails the whole calculation, never returning partial totals. */
    private fun totals(charges: List<ExpectedCharge>): Map<CurrencyCode, Money> =
        charges.groupBy { it.currencyCode }.entries.sortedBy { it.key.value }.associate { (currency, events) ->
            currency to events.fold(Money(0, currency)) { sum, event -> sum + Money(event.amountMinor, currency) }
        }
}
