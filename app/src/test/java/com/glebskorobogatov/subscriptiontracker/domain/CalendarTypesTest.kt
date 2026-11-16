package com.glebskorobogatov.subscriptiontracker.domain

import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.time.ClockDateProvider
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CalendarTypesTest {
    @Test fun frequencyRepresentsCalendarUnitsWithoutMonthApproximation() {
        assertEquals(Period.ofDays(14), Frequency(FrequencyUnit.WEEK, 2).toPeriod())
        assertEquals(Period.ofDays(14), Frequency(FrequencyUnit.DAY, 14).toPeriod())
        for (months in listOf(1, 3, 6)) {
            assertEquals(Period.ofMonths(months), Frequency(FrequencyUnit.MONTH, months).toPeriod())
        }
        assertEquals(Period.ofYears(1), Frequency(FrequencyUnit.YEAR, 1).toPeriod())
    }

    @Test fun calT16RejectsNonpositiveFrequencyCounts() {
        for (unit in FrequencyUnit.entries) for (count in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { Frequency(unit, count) }
        }
    }

    @Test fun calT16WeekConversionChecksOverflow() {
        val limit = Int.MAX_VALUE / 7
        assertEquals(Period.ofDays(limit * 7), Frequency(FrequencyUnit.WEEK, limit).toPeriod())
        assertThrows(ArithmeticException::class.java) { Frequency(FrequencyUnit.WEEK, limit + 1).toPeriod() }
        assertThrows(ArithmeticException::class.java) { Frequency(FrequencyUnit.WEEK, Int.MAX_VALUE).toPeriod() }
        assertEquals(Period.ofMonths(Int.MAX_VALUE), Frequency(FrequencyUnit.MONTH, Int.MAX_VALUE).toPeriod())
    }

    @Test fun calendarPeriodIsHalfOpenAndIndependentOfTimeZone() {
        val start = LocalDate.of(2028, 2, 29)
        val end = LocalDate.of(2028, 3, 2)
        val period = CalendarPeriod(start, end)
        assertTrue(start in period)
        assertTrue(LocalDate.of(2028, 3, 1) in period)
        assertFalse(end in period)
        assertFalse(start.minusDays(1) in period)
    }

    @Test fun calT16RejectsEmptyAndReversedCalendarPeriods() {
        val date = LocalDate.of(2027, 1, 31)
        assertThrows(IllegalArgumentException::class.java) { CalendarPeriod(date, date) }
        assertThrows(IllegalArgumentException::class.java) { CalendarPeriod(date, date.minusDays(1)) }
        assertThrows(java.time.DateTimeException::class.java) {
            CalendarPeriod(LocalDate.of(2027, 2, 29), date)
        }
    }

    @Test fun injectedClockProvidesDeterministicLocalDateAndZone() {
        val instant = Instant.parse("2027-01-31T23:30:00Z")
        val utc = ClockDateProvider(Clock.fixed(instant, ZoneId.of("UTC")))
        val local = ClockDateProvider(Clock.fixed(instant, ZoneId.of("Asia/Yekaterinburg")))
        assertEquals(LocalDate.of(2027, 1, 31), utc.today())
        assertEquals(LocalDate.of(2027, 2, 1), local.today())
        assertEquals(ZoneId.of("Asia/Yekaterinburg"), local.zoneId())
        assertEquals(local.today(), local.today())
    }

    @Test fun identifiersRoundTripCanonicalUuidsWithSeparateTypes() {
        val raw = "123e4567-e89b-12d3-a456-426614174000"
        val id = SubscriptionId.parse(raw)
        assertEquals(UUID.fromString(raw), id.value)
        assertEquals(raw, id.toString())
        assertEquals(id, SubscriptionId.parse(raw.uppercase()))
        val generated = SubscriptionId.random()
        assertEquals(generated, SubscriptionId.parse(generated.toString()))
    }

    @Test fun identifiersRejectMalformedAndAbbreviatedUuids() {
        val parsers: List<(String) -> Any> = listOf(SubscriptionId::parse)
        for (parse in parsers) for (text in listOf("", "not-a-uuid", "1-1-1-1-1", " 123e4567-e89b-12d3-a456-426614174000")) {
            assertThrows(IllegalArgumentException::class.java) { parse(text) }
        }
    }
}
