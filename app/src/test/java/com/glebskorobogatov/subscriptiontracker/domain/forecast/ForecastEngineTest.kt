package com.glebskorobogatov.subscriptiontracker.domain.forecast

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ForecastEngineTest {
    private val engine = ForecastEngine()
    private fun d(value: String) = LocalDate.parse(value)
    private fun sub(date: String = "2027-01-01", unit: FrequencyUnit = FrequencyUnit.MONTH, count: Int = 1,
        id: Long = 1, amount: Long = 100) = SubscriptionRecord(SubscriptionId(UUID(0,id)),"Сервис",RubPrice(amount),
            RecurringSchedule(RenewalAnchor.from(d(date)),Frequency(unit,count)))

    @Test fun fcT01ThirtyDaysAreHalfOpenAndIncludeToday() {
        val result = engine.calculate(listOf(sub(unit = FrequencyUnit.DAY)), d("2027-01-01")).next30Days
        assertEquals((1..31).map { LocalDate.of(2027,1,it) },result.charges.map { it.dueDate })
        assertEquals(3100L,result.totalMinor)
    }
    @Test fun fcT02ThirtyDaysCrossFebruary() {
        val result = engine.calculate(listOf(sub(unit = FrequencyUnit.DAY)), d("2027-01-29")).next30Days
        assertEquals(31,result.charges.size)
        assertEquals(d("2027-01-29"),result.charges.first().dueDate)
        assertEquals(d("2027-02-28"),result.charges.last().dueDate)
        assertEquals(d("2027-03-01"),result.untilExclusive)
    }
    @Test fun fcT03CurrentMonthExcludesPastAndIncludesTwoFollowingMonths() {
        val result = engine.calculate(listOf(sub("2026-10-01",FrequencyUnit.DAY)),d("2026-10-15"))
        assertEquals(d("2026-10-15"),result.charges.first().dueDate)
        assertEquals(d("2027-09-30"),result.charges.last().dueDate)
        assertEquals(listOf("2026-10","2026-11","2026-12"),result.months.keys.take(3).map { it.toString() })
        assertEquals(12,result.months.size)
    }
    @Test fun fcT04SixAndTwelveMonthsCrossYear() {
        val result = engine.calculate(listOf(sub("2026-10-15")),d("2026-10-15"))
        assertEquals(12,result.charges.size); assertEquals(12,result.months.size)
        assertEquals(d("2027-10-01"),result.untilExclusive)
    }
    @Test fun fcT06AutoEveryFourteenDays() {
        val result = engine.calculate(listOf(sub(unit = FrequencyUnit.DAY,count = 14)),d("2027-01-01"))
        assertEquals((0L..26L).map { d("2027-01-01").plusDays(it*14) },result.charges.map { it.dueDate })
        assertEquals(2700L,result.totalMinor)
    }
    @Test fun fcT08ExcludedStatesRegardlessOfDatesOrPaidAccess() {
        for(state in listOf(SubscriptionState.CANCELLED,SubscriptionState.ARCHIVED)) {
            for(date in listOf("2026-12-01","2027-01-01","2027-02-01")) {
                val s = sub(date).copy(state = state,cancelledAccessEndExclusive = d("2027-12-31"))
                val f = engine.calculate(listOf(s),d("2027-01-01"))
                assertTrue(f.charges.isEmpty()); assertEquals(0L,f.totalMinor); assertEquals(0L,f.next30Days.totalMinor)
            }
        }
    }
    @Test fun fcT09ForecastLookupNeverMutatesScheduleOrState() {
        val s = sub(); val original = s.copy()
        assertEquals(1,engine.calculate(listOf(s),d("2027-01-01")).next30Days.charges.size)
        engine.calculate(listOf(s),d("2037-01-01")); assertEquals(original,s)
    }
    @Test fun fcT10RecalculatesPriceScheduleDateStateAndMode() {
        val s = sub()
        fun forecast(value: SubscriptionRecord) = engine.calculate(listOf(value),d("2027-01-01"))
        assertEquals(2400L,forecast(s.copy(price = RubPrice(200))).totalMinor)
        assertEquals(d("2027-01-05"),forecast(s.copy(schedule = RecurringSchedule.starting(d("2027-01-05"),s.schedule.frequency))).charges.first().dueDate)
        val shifted = forecast(s.copy(schedule = RecurringSchedule.starting(d("2027-01-10"),s.schedule.frequency)))
        assertEquals(List(12) { 10 },shifted.charges.map { it.dueDate.dayOfMonth })
        assertEquals(27,forecast(s.copy(schedule = s.schedule.copy(frequency = Frequency(FrequencyUnit.WEEK,2)))).charges.size)
        assertTrue(forecast(s.copy(state = SubscriptionState.ARCHIVED)).charges.isEmpty())
        assertEquals(12,forecast(s).charges.size)
    }
    @Test fun fcT11MonthlyDetailMatchesTotalsIncludingEmptyMonths() {
        val result = engine.calculate(listOf(sub("2027-01-31"),sub("2027-02-05",id = 2)),d("2027-01-15"))
        assertEquals(result.charges,result.months.values.flatMap { it.charges })
        result.months.forEach { (month,group) ->
            assertEquals(month,group.month)
            assertTrue(group.charges.all { YearMonth.from(it.dueDate) == month })
            assertEquals(group.charges.sumOf { it.amountMinor },group.totalMinor)
        }
        val annual = engine.calculate(listOf(sub("2027-01-01",FrequencyUnit.YEAR)),d("2027-01-01"))
        assertTrue(annual.months.getValue(YearMonth.of(2027,2)).charges.isEmpty())
        assertEquals(0L,annual.months.getValue(YearMonth.of(2027,2)).totalMinor)
    }
    @Test fun fcT13DeterministicOrderIndependentOfInputOrder() {
        val a = sub(id = 2); val b = sub(id = 1); val c = sub("2027-01-02",id = 3)
        val result = engine.calculate(listOf(c,a,b),d("2027-01-01"))
        assertEquals(result,engine.calculate(listOf(b,a,c),d("2027-01-01")))
        assertEquals(listOf(b.id,a.id,c.id),result.charges.take(3).map { it.subscriptionId })
    }
    @Test fun fcT14ExactLongBoundaryAndOverflowAcrossMonths() {
        val s = sub(unit = FrequencyUnit.YEAR,amount = Long.MAX_VALUE-1)
        assertEquals(Long.MAX_VALUE,engine.calculate(listOf(s,sub(unit = FrequencyUnit.YEAR,id = 2,amount = 1)),d("2027-01-01")).totalMinor)
        assertThrows(ArithmeticException::class.java) { engine.calculate(listOf(s,sub(unit = FrequencyUnit.YEAR,id = 2,amount = 2)),d("2027-01-01")) }
        assertThrows(ArithmeticException::class.java) { engine.calculate(listOf(s.copy(schedule = sub().schedule)),d("2027-01-01")) }
    }
    @Test(timeout = 2000) fun calT17AncientDailyScheduleUsesEfficientLookup() {
        val s = sub("0001-01-01",FrequencyUnit.DAY); val today = LocalDate.of(900_000_000,1,1)
        val result = engine.calculate(listOf(s),today)
        assertEquals(today.lengthOfYear(),result.charges.size); assertEquals(today,result.charges.first().dueDate)
    }
    @Test fun emptyInvalidAndDateBoundaryInputsFailExplicitly() {
        val day = d("2027-01-01"); val empty = engine.calculate(emptyList(),day)
        assertTrue(empty.charges.isEmpty()); assertEquals(0L,empty.totalMinor); assertEquals(12,empty.months.size)
        assertThrows(IllegalArgumentException::class.java) { engine.calculate(listOf(sub(),sub()),day) }
        assertThrows(DateTimeException::class.java) { engine.calculate(emptyList(),LocalDate.MAX) }
        assertThrows(CalendarCalculationException::class.java) { ForecastEngine(DefaultRenewalCalendar(1)).calculate(listOf(sub(unit = FrequencyUnit.DAY)),day) }
    }
    @Test fun calT19DisplayPeriodStartDoesNotChangeForecast() {
        val s = sub(); val changed = s.copy(periodStart = PeriodStart(d("2026-12-20"),d("2027-01-01")))
        assertEquals(engine.calculate(listOf(s),d("2027-01-01")),engine.calculate(listOf(changed),d("2027-01-01")))
        assertEquals(s.schedule,changed.schedule)
    }
    @Test fun leapYearAndYearBoundaryKeepCalendarDates() {
        val yearly = sub("2028-02-29",FrequencyUnit.YEAR)
        assertEquals(d("2029-02-28"),engine.calculate(listOf(yearly),d("2029-01-01")).charges.single().dueDate)
        assertEquals(d("2032-02-29"),engine.calculate(listOf(yearly),d("2032-01-01")).charges.single().dueDate)
        val daily = engine.calculate(listOf(sub("2027-12-20",FrequencyUnit.DAY)),d("2027-12-20")).next30Days
        assertEquals(d("2028-01-19"),daily.charges.last().dueDate)
    }

    @Test fun ownerControlDatesUseInclusiveThirtyAndExclusiveCalendarYear() {
        val today = d("2026-11-16")
        val dates = listOf("2026-11-15","2026-11-16","2026-12-15","2026-12-16","2026-12-17","2027-10-31","2027-11-01")
        val result = engine.calculate(dates.mapIndexed { i,date -> sub(date,FrequencyUnit.YEAR,id = i+1L) },today)
        assertEquals(listOf("2026-11-16","2026-12-15","2026-12-16"),result.next30Days.charges.map { it.dueDate.toString() })
        assertEquals(d("2026-12-17"),result.next30Days.untilExclusive)
        assertFalse(result.charges.any { it.dueDate < today || it.dueDate >= d("2027-11-01") })
        assertTrue(result.charges.any { it.dueDate == d("2027-10-31") })
        assertEquals(d("2027-11-01"),result.untilExclusive)
    }
    @Test fun dailyAndWeeklyIncludeAllThirtyOneCalendarDates() {
        for ((unit,count) in listOf(FrequencyUnit.DAY to 31,FrequencyUnit.WEEK to 5)) {
            val result = engine.calculate(listOf(sub("2026-11-16",unit,amount = 123)),d("2026-11-16")).next30Days
            assertEquals(count,result.charges.size); assertEquals(count*123L,result.totalMinor)
            assertTrue(result.charges.all { it.dueDate < d("2026-12-17") })
        }
    }
    @Test fun arbitraryThirtyDayIntervalIsNotAMonthAndFutureFirstEventIsRespected() {
        val f = engine.calculate(listOf(sub("2027-01-31",FrequencyUnit.DAY,30)),d("2027-01-01"))
        assertEquals(listOf("2027-01-31","2027-03-02","2027-04-01"),f.charges.take(3).map { it.dueDate.toString() })
        assertEquals(listOf(d("2027-01-31")),f.next30Days.charges.map { it.dueDate })
    }
    @Test fun monthlyQuarterlyAndHalfYearlyRestoreOriginalDay() {
        for ((count,expected) in listOf(1 to listOf("2027-01-31","2027-02-28","2027-03-31"),
            3 to listOf("2027-01-31","2027-04-30","2027-07-31"),6 to listOf("2027-01-31","2027-07-31"))) {
            val f = engine.calculate(listOf(sub("2027-01-31",count = count)),d("2027-01-01"))
            assertEquals(expected,f.charges.take(expected.size).map { it.dueDate.toString() })
        }
    }
    @Test fun zeroForecastStillContainsTwelveChronologicalBuckets() {
        for (rows in listOf(emptyList(),listOf(sub("2029-01-01")))) {
            val f = engine.calculate(rows,d("2026-11-16"))
            assertEquals((0L..11L).map { YearMonth.of(2026,11).plusMonths(it) },f.months.keys.toList())
            assertEquals(0L,f.totalMinor); assertEquals(0L,f.next30Days.totalMinor)
            assertTrue(f.months.values.all { it.totalMinor == 0L && it.charges.isEmpty() })
        }
    }
    @Test fun shortWindowUsesExactlySameEventsAndCheckedKopecksAsBuckets() {
        val f = engine.calculate(listOf(sub(unit = FrequencyUnit.DAY,amount = 29950),sub(id = 2,amount = 1)),d("2027-01-01"))
        assertEquals(31*29950L+1,f.next30Days.totalMinor)
        assertEquals(f.charges.filter { it.dueDate < f.next30Days.untilExclusive },f.next30Days.charges)
        assertEquals(f.totalMinor,f.months.values.sumOf { it.totalMinor })
        assertEquals(f.charges,f.months.values.flatMap { it.charges })
    }
    @Test fun movingTodayRebuildsBothWindowsWithoutChangingSource() {
        val rows = listOf(sub(unit = FrequencyUnit.DAY)); val copy = rows.toList()
        val before = engine.calculate(rows,d("2027-01-31")); val after = engine.calculate(rows,d("2027-02-01"))
        assertEquals(d("2027-01-31"),before.next30Days.charges.first().dueDate)
        assertEquals(d("2027-02-01"),after.next30Days.charges.first().dueDate)
        assertEquals(YearMonth.of(2027,2),after.months.keys.first()); assertEquals(rows,copy)
    }
    @Test fun sameNamesStaySeparateAndMoneyDtoHasNoCurrencyOrFilter() {
        val f = engine.calculate(listOf(sub(id = 2,amount = 29950),sub(id = 1,amount = 101)),d("2027-01-01"))
        assertEquals(30051L,f.next30Days.totalMinor); assertEquals(2,f.next30Days.charges.size)
        assertEquals(listOf(SubscriptionId(UUID(0,1)),SubscriptionId(UUID(0,2))),f.next30Days.charges.map { it.subscriptionId })
        assertFalse(ExpectedCharge::class.java.declaredFields.any { it.name.contains("currency",true) })
        assertFalse(ForecastResult::class.java.declaredFields.any { it.name in setOf("totalsByCurrency","availableCurrencies") })
    }
    @Test fun hundredActiveSubscriptionsIncludingDailyCalculateWithoutTruncation() {
        val rows = (1L..100L).map { sub("0001-01-01",FrequencyUnit.DAY,id = it,amount = 123) }
        val started = System.nanoTime(); val f = engine.calculate(rows,d("2026-11-16"))
        val elapsed = (System.nanoTime()-started)/1_000_000.0
        // 16.11.2026..01.11.2027 = 350 dates, independently counted by month lengths.
        assertEquals(35000,f.charges.size); assertEquals(3100,f.next30Days.charges.size)
        assertEquals(4305000L,f.totalMinor); assertEquals(381300L,f.next30Days.totalMinor)
        println("Synthetic 100 daily subscriptions: events=${f.charges.size}, elapsedMs=$elapsed (cloud JVM only)")
    }
    @Test fun aggregateEventBudgetFailsExplicitlyInsteadOfReturningPartialForecast() {
        val limited = ForecastEngine(maxEventsPerSnapshot = 12)
        assertThrows(CalendarCalculationException::class.java) {
            limited.calculate(listOf(sub(id = 1),sub(id = 2)),d("2027-01-01"))
        }
        assertEquals(12,limited.calculate(listOf(sub()),d("2027-01-01")).charges.size)
        assertThrows(IllegalArgumentException::class.java) { ForecastEngine(maxEventsPerSnapshot = 0) }
    }
    @Test fun eventBudgetDoesNotLimitNumberOfSubscriptionsWithNoEventsInWindow() {
        val rows = (1L..1000L).map { sub("2029-01-01",id = it) }
        val result = ForecastEngine(maxEventsPerSnapshot = 1).calculate(rows,d("2027-01-01"))
        assertEquals(12,result.months.size); assertEquals(0L,result.totalMinor)
        assertTrue(result.charges.isEmpty()); assertEquals(0L,result.next30Days.totalMinor)
    }
    @Test fun unrepresentableNextRenewalIsCalculationFailureRatherThanSuccessfulZero() {
        val row = sub("2027-01-01",FrequencyUnit.YEAR,Int.MAX_VALUE)
        assertThrows(CalendarCalculationException::class.java) {
            engine.calculate(listOf(row),d("2027-01-02"))
        }
    }
}
