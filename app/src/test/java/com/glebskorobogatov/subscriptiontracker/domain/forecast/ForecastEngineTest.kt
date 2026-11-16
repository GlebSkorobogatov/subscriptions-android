package com.glebskorobogatov.subscriptiontracker.domain.forecast

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ForecastEngineTest {
    private val engine = ForecastEngine()
    private val calendar = DefaultRenewalCalendar()
    private val rub = CurrencyCode.of("RUB")
    private fun d(value: String) = LocalDate.parse(value)
    private fun sub(
        date: String = "2027-01-01", unit: FrequencyUnit = FrequencyUnit.MONTH, count: Int = 1,
        mode: RenewalMode = RenewalMode.AUTO, id: Long = 1, currency: String = "RUB", amount: Long = 100,
    ) = Subscription(SubscriptionId(UUID(0, id)), mode,
        RenewalSchedule(RenewalAnchor.from(d(date)), Frequency(unit, count)), Money(amount, CurrencyCode.of(currency)))

    @Test fun fcT01ThirtyDaysAreHalfOpenAndIncludeToday() {
        val result = engine.next30Days(listOf(sub(unit = FrequencyUnit.DAY)), d("2027-01-01"))
        assertEquals((1..30).map { LocalDate.of(2027, 1, it) }, result.charges.map { it.dueDate })
        assertEquals((0L..29L).toList(), result.charges.map { it.cycleIndex })
        assertEquals(Money(3000, rub), result.totalsByCurrency[rub])
    }
    @Test fun fcT02ThirtyDaysCrossFebruary() {
        val result = engine.next30Days(listOf(sub(unit = FrequencyUnit.DAY)), d("2027-01-29"))
        assertEquals(30, result.charges.size)
        assertEquals(d("2027-01-29"), result.charges.first().dueDate)
        assertEquals(d("2027-02-27"), result.charges.last().dueDate)
        assertEquals(d("2027-02-28"), result.untilExclusive)
    }
    @Test fun fcT03CurrentMonthExcludesPastAndIncludesTwoFollowingMonths() {
        val result = engine.calendarMonths(listOf(sub("2026-10-01", FrequencyUnit.DAY)), d("2026-10-15"), 3)
        assertEquals(d("2026-10-15"), result.charges.first().dueDate)
        assertEquals(d("2026-12-31"), result.charges.last().dueDate)
        assertEquals(listOf("2026-10", "2026-11", "2026-12"), result.months.keys.map { it.toString() })
    }
    @Test fun fcT04SixAndTwelveMonthsCrossYear() {
        for (count in listOf(6, 12)) {
            val result = engine.calendarMonths(listOf(sub("2026-10-15")), d("2026-10-15"), count)
            assertEquals(count, result.charges.size)
            assertEquals(count, result.months.size)
            assertEquals(YearMonth.of(2026, 10).plusMonths(count.toLong()).atDay(1), result.untilExclusive)
        }
    }
    @Test fun fcT05AndMoneyT04CurrenciesNeverMix() {
        val subscriptions = listOf(sub(currency = "RUB", amount = 49900),
            sub(id = 2, currency = "USD", amount = 999), sub(id = 3, currency = "JPY", amount = 1200))
        val all = engine.next30Days(subscriptions, d("2027-01-01"))
        assertEquals(mapOf("JPY" to 1200L, "RUB" to 49900L, "USD" to 999L),
            all.totalsByCurrency.mapKeys { it.key.value }.mapValues { it.value.amountMinor })
        val usd = engine.next30Days(subscriptions, d("2027-01-01"), CurrencyCode.of("USD"))
        assertEquals(listOf("USD"), usd.charges.map { it.currencyCode.value })
        assertEquals(1, usd.months.values.single().totalsByCurrency.size)
        assertEquals(listOf("JPY", "RUB", "USD"), usd.availableCurrencies.map { it.value })
    }
    @Test fun fcT06AutoEveryFourteenDays() {
        val result = engine.calendarMonths(listOf(sub(unit = FrequencyUnit.DAY, count = 14)), d("2027-01-01"), 3)
        assertEquals((0L..6L).map { d("2027-01-01").plusDays(it * 14) }, result.charges.map { it.dueDate })
        assertEquals(Money(700, rub), result.totalsByCurrency[rub])
    }
    @Test fun fcT07ManualProducesExactlyOneUntilExplicitConfirmation() {
        val s = sub(mode = RenewalMode.MANUAL)
        assertEquals(1, engine.calendarMonths(listOf(s), d("2026-12-31"), 12).charges.size)
        assertEquals(1, engine.calendarMonths(listOf(s), d("2027-01-01"), 12).charges.size)
        assertTrue(engine.calendarMonths(listOf(s), d("2027-01-02"), 12).charges.isEmpty())
        val renewed = SubscriptionLifecycleService().confirmRenewal(s, OperationId(UUID(0, 5)),
            d("2027-01-02"), s.price, d("2027-02-02")).subscription
        assertEquals(listOf(d("2027-02-02")), engine.calendarMonths(listOf(renewed), d("2027-01-02"), 12).charges.map { it.dueDate })
    }
    @Test fun fcT08ExcludedStatesRegardlessOfDatesOrPaidAccess() {
        for (state in listOf(LifecycleState.PAYMENT_ISSUE, LifecycleState.CANCELLATION_SCHEDULED, LifecycleState.ARCHIVED)) {
            for (date in listOf("2026-12-01", "2027-01-01", "2027-02-01")) {
                val s = sub(date).copy(state = state, paidAccessEnd = d("2027-12-31"))
                assertTrue(engine.calendarMonths(listOf(s), d("2027-01-01"), 12).charges.isEmpty())
            }
        }
        assertTrue(engine.next30Days(listOf(sub(mode = RenewalMode.MANUAL)), d("2027-01-02")).charges.isEmpty())
    }
    @Test fun fcT09HistoryIsNotAnInputAndAutoDoesNotCreatePayments() {
        val s = sub()
        val history = mutableListOf<PaymentIntent>()
        val before = engine.next30Days(listOf(s), d("2027-01-01"))
        assertTrue(history.isEmpty())
        val result = SubscriptionLifecycleService().addHistoricalPayment(s, OperationId(UUID(0, 5)), d("2027-01-01"), s.price)
        history += result.paymentIntent!!
        val refunds = mutableListOf(Money(10, rub)) // External history has no engine input path.
        assertEquals(before, engine.next30Days(listOf(result.subscription), d("2027-01-01")))
        assertEquals(1, history.size)
        assertEquals(listOf(Money(10, rub)), refunds)
        assertEquals(0L, s.schedule.nextCycleIndex)
    }
    @Test fun fcT10RecalculatesPriceScheduleDateStateAndMode() {
        val s = sub()
        fun forecast(value: Subscription) = engine.calendarMonths(listOf(value), d("2027-01-01"), 3)
        assertEquals(Money(600, rub), forecast(s.copy(price = Money(200, rub))).totalsByCurrency[rub])
        assertEquals(d("2027-01-05"), forecast(s.copy(schedule = calendar.moveNextOnly(s.schedule, d("2027-01-05")))).charges.first().dueDate)
        val shifted = forecast(s.copy(schedule = calendar.moveFutureSeries(s.schedule, d("2027-01-10"))))
        assertEquals(listOf(10, 10, 10), shifted.charges.map { it.dueDate.dayOfMonth })
        assertEquals(7, forecast(s.copy(schedule = s.schedule.copy(frequency = Frequency(FrequencyUnit.WEEK, 2)))).charges.size)
        assertEquals(1, forecast(s.copy(mode = RenewalMode.MANUAL)).charges.size)
        assertTrue(forecast(s.copy(state = LifecycleState.ARCHIVED)).charges.isEmpty())
        assertEquals(3, forecast(s).charges.size)
    }
    @Test fun fcT11MonthlyDetailMatchesTotalsIncludingEmptyMonths() {
        val result = engine.calendarMonths(listOf(sub("2027-01-31"), sub("2027-02-05", id = 2, currency = "USD")), d("2027-01-15"), 3)
        assertEquals(result.charges, result.months.values.flatMap { it.charges })
        result.months.forEach { (month, group) ->
            assertTrue(group.charges.all { YearMonth.from(it.dueDate) == month })
            group.totalsByCurrency.forEach { (currency, total) ->
                assertEquals(group.charges.filter { it.currencyCode == currency }.sumOf { it.amountMinor }, total.amountMinor)
            }
        }
        val manual = engine.calendarMonths(listOf(sub(mode = RenewalMode.MANUAL)), d("2027-01-01"), 3)
        assertTrue(manual.months[YearMonth.of(2027, 2)]!!.charges.isEmpty())
        assertTrue(manual.months[YearMonth.of(2027, 2)]!!.totalsByCurrency.isEmpty())
    }
    @Test fun fcT12AvailableCurrenciesRecomputedAfterRemoval() {
        val rubSub = sub()
        val usdSub = sub(id = 2, currency = "USD")
        assertEquals(2, engine.next30Days(listOf(rubSub, usdSub), d("2027-01-01")).availableCurrencies.size)
        val result = engine.next30Days(listOf(rubSub), d("2027-01-01"), CurrencyCode.of("USD"))
        assertEquals(listOf(rub), result.availableCurrencies)
        assertTrue(result.charges.isEmpty())
        assertTrue(engine.next30Days(emptyList(), d("2027-01-01")).availableCurrencies.isEmpty())
    }
    @Test fun fcT13DeterministicOrderIndependentOfInputOrder() {
        val a = sub(id = 2)
        val b = sub(id = 1)
        val c = sub("2027-01-02", id = 3)
        val result = engine.calendarMonths(listOf(c, a, b), d("2027-01-01"), 3)
        assertEquals(result, engine.calendarMonths(listOf(b, a, c), d("2027-01-01"), 3))
        assertEquals(listOf(b.id, a.id, c.id), result.charges.take(3).map { it.subscriptionId })
        assertEquals(result, engine.calendarMonths(listOf(c, a, b), d("2027-01-01"), 3))
    }
    @Test fun fcT14ExactLongBoundaryAndOverflowAcrossMonths() {
        val s = sub(amount = Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE, engine.next30Days(listOf(s, sub(id = 2, amount = 1)), d("2027-01-01")).totalsByCurrency[rub]!!.amountMinor)
        assertThrows(ArithmeticException::class.java) {
            engine.next30Days(listOf(s, sub(id = 2, amount = 2)), d("2027-01-01"))
        }
        assertThrows(ArithmeticException::class.java) { engine.calendarMonths(listOf(s), d("2027-01-01"), 3) }
        // Independent currencies can each reach Long.MAX_VALUE without a synthetic combined sum.
        assertEquals(2, engine.next30Days(listOf(sub(amount = Long.MAX_VALUE),
            sub(id = 2, currency = "JPY", amount = Long.MAX_VALUE)), d("2027-01-01")).totalsByCurrency.size)
    }
    @Test fun oneOffMovePreservesIndexesWithoutDuplicatesAndRestoresAnchorDay() {
        val original = sub("2027-01-31").copy(schedule = sub("2027-01-31").schedule.copy(nextCycleIndex = 1))
        val moved = original.copy(schedule = calendar.moveNextOnly(original.schedule, d("2027-03-02")))
        val result = engine.calendarMonths(listOf(moved), d("2027-02-01"), 3)
        assertEquals(listOf("2027-03-02", "2027-03-31", "2027-04-30"), result.charges.map { it.dueDate.toString() })
        assertEquals(listOf(1L, 2L, 3L), result.charges.map { it.cycleIndex })
        assertEquals(result.charges.size, result.charges.map { it.subscriptionId to it.cycleIndex }.distinct().size)
        assertEquals(original.schedule.anchor, moved.schedule.anchor)
        val after = engine.next30Days(listOf(moved), d("2027-03-03"))
        assertEquals(2L, after.charges.single().cycleIndex)
    }
    @Test(timeout = 2000) fun calT17AncientDailyScheduleUsesEfficientLookup() {
        val s = sub("0001-01-01", FrequencyUnit.DAY)
        val today = LocalDate.of(900_000_000, 1, 1)
        val result = engine.calendarMonths(listOf(s), today, 12)
        assertEquals(today.lengthOfYear(), result.charges.size)
        assertEquals(today.toEpochDay() - d("0001-01-01").toEpochDay(), result.charges.first().cycleIndex)
    }
    @Test fun emptyInvalidAndDateBoundaryInputsFailExplicitly() {
        val day = d("2027-01-01")
        assertTrue(engine.calculate(listOf(sub()), day, day).charges.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { engine.calculate(emptyList(), day, day.minusDays(1)) }
        assertThrows(IllegalArgumentException::class.java) { engine.calendarMonths(emptyList(), day, 4) }
        assertThrows(IllegalArgumentException::class.java) { engine.next30Days(listOf(sub(), sub()), day) }
        assertThrows(DateTimeException::class.java) { engine.next30Days(emptyList(), LocalDate.MAX) }
        assertThrows(IllegalArgumentException::class.java) {
            engine.calculate(emptyList(), LocalDate.MIN, LocalDate.MAX)
        }
        val invalid = sub().copy(schedule = sub().schedule.copy(nextCycleIndex = Long.MAX_VALUE))
        assertThrows(CalendarCalculationException::class.java) { engine.next30Days(listOf(invalid), day) }
    }
    @Test fun calT19DisplayPeriodStartDoesNotChangeForecast() {
        val s = sub()
        val changed = s.copy(periodStartOverride = d("2026-12-20"))
        assertEquals(engine.calendarMonths(listOf(s), d("2027-01-01"), 12),
            engine.calendarMonths(listOf(changed), d("2027-01-01"), 12))
        assertEquals(s.schedule, changed.schedule)
    }
    @Test fun leapYearAndYearBoundaryKeepCalendarDates() {
        val yearly = sub("2028-02-29", FrequencyUnit.YEAR)
        assertEquals(d("2029-02-28"), engine.calendarMonths(listOf(yearly), d("2029-01-01"), 3).charges.single().dueDate)
        assertEquals(d("2032-02-29"), engine.calendarMonths(listOf(yearly), d("2032-01-01"), 3).charges.single().dueDate)
        val daily = engine.next30Days(listOf(sub("2027-12-20", FrequencyUnit.DAY)), d("2027-12-20"))
        assertEquals(d("2028-01-18"), daily.charges.last().dueDate)
    }
}
