package com.glebskorobogatov.subscriptiontracker.domain.forecast

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
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
        id: Long = 1, amount: Long = 100,
    ) = SubscriptionRecord(SubscriptionId(UUID(0,id)),"Сервис",RubPrice(amount),RecurringSchedule(RenewalAnchor.from(d(date)),Frequency(unit,count)))

    @Test fun fcT01ThirtyDaysAreHalfOpenAndIncludeToday() {
        val result = engine.next30Days(listOf(sub(unit = FrequencyUnit.DAY)), d("2027-01-01"))
        assertEquals((1..30).map { LocalDate.of(2027, 1, it) }, result.charges.map { it.dueDate })
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

    @Test fun fcT06AutoEveryFourteenDays() {
        val result = engine.calendarMonths(listOf(sub(unit = FrequencyUnit.DAY, count = 14)), d("2027-01-01"), 3)
        assertEquals((0L..6L).map { d("2027-01-01").plusDays(it * 14) }, result.charges.map { it.dueDate })
        assertEquals(Money(700, rub), result.totalsByCurrency[rub])
    }

    @Test fun fcT08ExcludedStatesRegardlessOfDatesOrPaidAccess() {
        for (state in listOf(SubscriptionState.CANCELLED, SubscriptionState.ARCHIVED)) {
            for (date in listOf("2026-12-01", "2027-01-01", "2027-02-01")) {
                val s = sub(date).copy(state = state, cancelledAccessEndExclusive = d("2027-12-31"))
                assertTrue(engine.calendarMonths(listOf(s), d("2027-01-01"), 12).charges.isEmpty())
            }
        }
    }
    @Test fun fcT09ForecastLookupNeverMutatesScheduleOrState() {
        val s = sub(); val original = s.copy()
        assertEquals(1, engine.next30Days(listOf(s), d("2027-01-01")).charges.size)
        engine.calendarMonths(listOf(s), d("2037-01-01"), 12)
        assertEquals(original, s)
    }
    @Test fun fcT10RecalculatesPriceScheduleDateStateAndMode() {
        val s = sub()
        fun forecast(value: SubscriptionRecord) = engine.calendarMonths(listOf(value), d("2027-01-01"), 3)
        assertEquals(Money(600, rub), forecast(s.copy(price = RubPrice(200))).totalsByCurrency[rub])
        assertEquals(d("2027-01-05"), forecast(s.copy(schedule = RecurringSchedule.starting(d("2027-01-05"),s.schedule.frequency))).charges.first().dueDate)
        val shifted = forecast(s.copy(schedule = RecurringSchedule.starting(d("2027-01-10"),s.schedule.frequency)))
        assertEquals(listOf(10, 10, 10), shifted.charges.map { it.dueDate.dayOfMonth })
        assertEquals(7, forecast(s.copy(schedule = s.schedule.copy(frequency = Frequency(FrequencyUnit.WEEK, 2)))).charges.size)
        assertTrue(forecast(s.copy(state = SubscriptionState.ARCHIVED)).charges.isEmpty())
        assertEquals(3, forecast(s).charges.size)
    }
    @Test fun fcT11MonthlyDetailMatchesTotalsIncludingEmptyMonths() {
        val result = engine.calendarMonths(listOf(sub("2027-01-31"), sub("2027-02-05", id = 2)), d("2027-01-15"), 3)
        assertEquals(result.charges, result.months.values.flatMap { it.charges })
        result.months.forEach { (month, group) ->
            assertTrue(group.charges.all { YearMonth.from(it.dueDate) == month })
            group.totalsByCurrency.forEach { (currency, total) ->
                assertEquals(group.charges.filter { it.currencyCode == currency }.sumOf { it.amountMinor }, total.amountMinor)
            }
        }
        val manual = engine.calendarMonths(listOf(sub("2027-01-01",FrequencyUnit.YEAR)), d("2027-01-01"), 3)
        assertTrue(manual.months[YearMonth.of(2027, 2)]!!.charges.isEmpty())
        assertTrue(manual.months[YearMonth.of(2027, 2)]!!.totalsByCurrency.isEmpty())
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

    }

    @Test(timeout = 2000) fun calT17AncientDailyScheduleUsesEfficientLookup() {
        val s = sub("0001-01-01", FrequencyUnit.DAY)
        val today = LocalDate.of(900_000_000, 1, 1)
        val result = engine.calendarMonths(listOf(s), today, 12)
        assertEquals(today.lengthOfYear(), result.charges.size)
        assertEquals(today,result.charges.first().dueDate)
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

    }
    @Test fun calT19DisplayPeriodStartDoesNotChangeForecast() {
        val s = sub()
        val changed = s.copy(periodStart = PeriodStart(d("2026-12-20"), d("2027-01-01")))
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
