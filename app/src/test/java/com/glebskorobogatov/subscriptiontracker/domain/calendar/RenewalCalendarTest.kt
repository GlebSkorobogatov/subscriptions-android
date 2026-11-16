package com.glebskorobogatov.subscriptiontracker.domain.calendar

import com.glebskorobogatov.subscriptiontracker.domain.subscription.RecurringSchedule
import com.glebskorobogatov.subscriptiontracker.domain.model.Frequency
import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class RenewalCalendarTest {
    private val calendar = DefaultRenewalCalendar()
    private fun date(text: String): LocalDate = LocalDate.parse(text)
    private fun schedule(start: String, unit: FrequencyUnit = FrequencyUnit.MONTH, count: Int = 1) =
        RecurringSchedule(RenewalAnchor.from(date(start)), Frequency(unit, count))
    private fun dates(schedule: RecurringSchedule, count: Int) =
        (0 until count).map { calendar.plannedCycleDate(schedule, it.toLong()) }
    private fun expect(schedule: RecurringSchedule, vararg expected: String) =
        assertEquals(expected.map(::date), dates(schedule, expected.size))

    @Test fun calT01Restores31AfterShortMonths() = expect(schedule("2027-01-31"),
        "2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30", "2027-05-31")

    @Test fun calT02Restores29InLeapAndNonLeapYears() {
        expect(schedule("2028-01-29"), "2028-01-29", "2028-02-29", "2028-03-29")
        expect(schedule("2027-01-29"), "2027-01-29", "2027-02-28", "2027-03-29")
    }

    @Test fun calT03QuarterlySeriesUsesLeapFebruary() = expect(schedule("2027-11-30", count = 3),
        "2027-11-30", "2028-02-29", "2028-05-30", "2028-08-30")

    @Test fun calT04HalfYearlySeriesPreservesAnchor() = expect(schedule("2027-08-31", count = 6),
        "2027-08-31", "2028-02-29", "2028-08-31")

    @Test fun calT05YearlyLeapDayReturnsIn2032() = expect(schedule("2028-02-29", FrequencyUnit.YEAR),
        "2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29")

    @Test fun calT06CrossesYearBoundary() = expect(schedule("2027-12-31"),
        "2027-12-31", "2028-01-31", "2028-02-29", "2028-03-31")

    @Test fun calT07FortnightUsesCalendarDays() = expect(schedule("2027-01-01", FrequencyUnit.DAY, 14),
        "2027-01-01", "2027-01-15", "2027-01-29", "2027-02-12")

    @Test fun calT08WeeksMatchSevenDayIntervals() {
        assertEquals(dates(schedule("2027-01-01", FrequencyUnit.DAY, 14), 50),
            dates(schedule("2027-01-01", FrequencyUnit.WEEK, 2), 50))
    }

    @Test fun calT09ArbitraryCalendarIntervals() {
        expect(schedule("2027-01-01", count = 2), "2027-01-01", "2027-03-01", "2027-05-01")
        expect(schedule("2027-01-01", count = 4), "2027-01-01", "2027-05-01", "2027-09-01")
        expect(schedule("2027-01-01", FrequencyUnit.DAY, 5), "2027-01-01", "2027-01-06", "2027-01-11")
        expect(schedule("2028-02-29", FrequencyUnit.YEAR, 4), "2028-02-29", "2032-02-29", "2036-02-29")
    }

    @Test fun calT10ExplicitDayIsIndependentOfShortInitialMonth() {
        val schedule = RecurringSchedule(RenewalAnchor(YearMonth.of(2027, 2), 31), Frequency(FrequencyUnit.MONTH, 1))
        expect(schedule, "2027-02-28", "2027-03-31", "2027-04-30")
        val leapAnchor = RecurringSchedule(RenewalAnchor(YearMonth.of(2027, 2), 29), Frequency(FrequencyUnit.YEAR, 1))
        expect(leapAnchor, "2027-02-28", "2028-02-29", "2029-02-28")
    }





    @Test fun calT14DateIsCurrentOnDueDayAndNextCycleAfterwards() {
        val schedule = schedule("2027-01-31")
        assertEquals(date("2027-01-31"), calendar.firstOnOrAfter(schedule, date("2027-01-31")))
        assertEquals(date("2027-02-28"), calendar.firstOnOrAfter(schedule, date("2027-02-01")))
    }

    @Test fun calT15TimeZonesCannotShiftPaymentDates() {
        val originalZone = TimeZone.getDefault()
        val schedule = schedule("2028-02-29", FrequencyUnit.YEAR)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Amsterdam"))
            val first = dates(schedule, 5)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Yekaterinburg"))
            assertEquals(first, dates(schedule, 5))
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test fun calT16RejectsInvalidModelsAndNegativeIndices() {
        assertThrows(IllegalArgumentException::class.java) { Frequency(FrequencyUnit.MONTH, 0) }
        assertThrows(IllegalArgumentException::class.java) { Frequency(FrequencyUnit.DAY, -1) }
        assertThrows(IllegalArgumentException::class.java) { RenewalAnchor(YearMonth.of(2027, 1), 32) }
        assertThrows(IllegalArgumentException::class.java) { calendar.plannedCycleDate(schedule("2027-01-01"), -1) }
        assertThrows(DateTimeException::class.java) {
            RecurringSchedule(RenewalAnchor(YearMonth.of(2027, 2), 31), Frequency(FrequencyUnit.DAY, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecurringSchedule(RenewalAnchor(YearMonth.of(2027, 4), 31), Frequency(FrequencyUnit.YEAR, 1))
        }
    }

    @Test fun calT16OverflowAndDateExhaustionAreControlled() {
        for (unit in FrequencyUnit.entries) {
            val schedule = schedule("2027-01-01", unit, Int.MAX_VALUE)
            assertThrows(CalendarCalculationException::class.java) { calendar.plannedCycleDate(schedule, Long.MAX_VALUE) }

        }
        val last = RecurringSchedule(RenewalAnchor.from(LocalDate.MAX), Frequency(FrequencyUnit.DAY, 1))
        assertEquals(LocalDate.MAX, calendar.firstOnOrAfter(last, LocalDate.MAX))
        assertThrows(CalendarCalculationException::class.java) { calendar.plannedCycleDate(last, 1) }
    }

    @Test(timeout = 2000) fun calT17SearchDoesNotWalkBillionYearHistory() {
        val schedule = RecurringSchedule(RenewalAnchor.from(LocalDate.MIN), Frequency(FrequencyUnit.DAY, 1))
        val start = date("2027-01-01")
        val actual = calendar.upcomingEvents(schedule, start, date("2028-01-01")).toList()
        assertEquals(365, actual.size)
        assertEquals(start, actual.first())
        assertEquals(date("2027-12-31"), actual.last())
    }

    @Test fun rangeIsHalfOpenAndRespectsNextCycleIndex() {
        val schedule = schedule("2027-01-31")
        assertEquals(listOf(date("2027-01-31"), date("2027-02-28"), date("2027-03-31")), calendar.upcomingEvents(schedule, date("2027-01-01"), date("2027-04-30")).toList())
        assertEquals(listOf(date("2027-03-31")), calendar.upcomingEvents(schedule, date("2027-03-31"), date("2027-04-01")).toList())
        assertTrue(calendar.upcomingEvents(schedule, date("2027-01-01"), date("2027-01-01")).none())
        assertThrows(IllegalArgumentException::class.java) {
            calendar.upcomingEvents(schedule, date("2027-02-01"), date("2027-01-01"))
        }
    }


    @Test fun manyValidSchedulesAreIncreasingDeterministicAndKeepAnchor() {
        for (unit in FrequencyUnit.entries) for (count in listOf(1, 2, 3, 6, 17)) for (day in 28..31) {
            val schedule = schedule("2028-01-$day", unit, count)
            val anchor = schedule.anchor
            val first = dates(schedule, 120)
            assertTrue(first.zipWithNext().all { (a, b) -> a < b })
            assertEquals(first, dates(schedule, 120))
            assertEquals(anchor, schedule.anchor)
            assertEquals(first.size, first.toSet().size)
        }
    }

    @Test fun boundedSearchAgreesWithEnumeratedShortSeries() {
        for (unit in FrequencyUnit.entries) {
            val schedule = schedule("2020-01-31", unit, 2)
            val all = (1L..199L).map { calendar.plannedCycleDate(schedule, it) }
            val start = all[30].plusDays(1)
            val end = all[80]
            assertEquals(all.filter { it >= start && it < end }, calendar.upcomingEvents(schedule, start, end).toList())
        }
    }

    @Test(timeout = 2000) fun hugeRangeIsLazyAndOversizedMaterializationFailsExplicitly() {
        val schedule = RecurringSchedule(RenewalAnchor.from(LocalDate.MIN), Frequency(FrequencyUnit.DAY, 1))
        assertEquals(listOf(LocalDate.MIN, LocalDate.MIN.plusDays(1), LocalDate.MIN.plusDays(2)),
            calendar.upcomingEvents(schedule, LocalDate.MIN, LocalDate.MAX).take(3).toList())
        assertThrows(CalendarCalculationException::class.java) {
            DefaultRenewalCalendar(3).upcomingEvents(schedule, LocalDate.MIN, LocalDate.MAX).toList()
        }
        assertEquals(3, DefaultRenewalCalendar(3).upcomingEvents(schedule, LocalDate.MIN, LocalDate.MIN.plusDays(3)).count())
    }

    @Test fun rangeExhaustionNearMaxDateDoesNotWrapOrInventEvents() {
        val schedule = RecurringSchedule(RenewalAnchor.from(LocalDate.MAX.minusDays(5)), Frequency(FrequencyUnit.DAY, 3))
        assertEquals(listOf(LocalDate.MAX.minusDays(5), LocalDate.MAX.minusDays(2)),
            calendar.upcomingEvents(schedule, LocalDate.MAX.minusDays(5), LocalDate.MAX).toList())
        assertNull(calendar.firstOnOrAfter(schedule, LocalDate.MAX))
    }
}
