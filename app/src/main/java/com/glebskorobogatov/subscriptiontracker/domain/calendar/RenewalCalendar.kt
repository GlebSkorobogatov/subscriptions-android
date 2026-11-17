package com.glebskorobogatov.subscriptiontracker.domain.calendar

import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import com.glebskorobogatov.subscriptiontracker.domain.subscription.RecurringSchedule
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

interface RenewalCalendar {
    /** Returns a regular series boundary from the original anchor. */
    fun plannedCycleDate(schedule: RecurringSchedule, index: Long): LocalDate
    fun upcomingEvents(schedule: RecurringSchedule, from: LocalDate, untilExclusive: LocalDate): Sequence<LocalDate>
}

class CalendarCalculationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Pure calendar arithmetic. No clock, zone, subscription state or persistence dependency. */
class DefaultRenewalCalendar(private val maxEventsPerQuery: Int = 10_000) : RenewalCalendar {
    init {
        require(maxEventsPerQuery > 0) { "Event limit must be positive" }
    }

    override fun plannedCycleDate(schedule: RecurringSchedule, index: Long): LocalDate {
        require(index >= 0) { "Cycle index must not be negative" }
        validate(schedule)
        return regularDate(schedule, index)
    }

    /** Inclusive lookup; unlike a half-open range it can also return LocalDate.MAX. */
    fun firstOnOrAfter(schedule: RecurringSchedule, from: LocalDate): LocalDate? {
        validate(schedule)
        return regularDateOrNull(schedule, lowerBound(schedule, from))
    }

    /** Indexed counterpart of firstOnOrAfter; uses the same bounded binary search. */
    fun firstCycleIndexOnOrAfter(schedule: RecurringSchedule, from: LocalDate): Long? {
        validate(schedule)
        val index = lowerBound(schedule, from)
        return index.takeIf { regularDateOrNull(schedule, it) != null }
    }

    override fun upcomingEvents(
        schedule: RecurringSchedule,
        from: LocalDate,
        untilExclusive: LocalDate,
    ): Sequence<LocalDate> {
        require(from <= untilExclusive) { "Range end must not precede start" }
        validate(schedule)
        if (from == untilExclusive) return emptySequence()
        val first = lowerBound(schedule, from)
        return sequence {
            var index = first
            var emitted = 0
            while (true) {
                val date = regularDateOrNull(schedule, index) ?: break
                if (date >= untilExclusive) break
                if (emitted == maxEventsPerQuery) {
                    throw CalendarCalculationException("Too many events; narrow the query range")
                }
                yield(date)
                emitted++
                index = Math.incrementExact(index)
            }
        }
    }

    /** Previous regular boundary, including the calculated boundary before the first event.
     * This also permits a precise period before the first expected event.
     */
    fun previousRegularBoundary(schedule: RecurringSchedule, due: LocalDate): LocalDate? {
        val index = firstCycleIndexOnOrAfter(schedule, due) ?: return null
        require(plannedCycleDate(schedule, index) == due) { "Expected a series boundary" }
        return try {
            if (index > 0) regularDate(schedule, index - 1) else {
                val anchor = schedule.anchor
                val count = schedule.frequency.count.toLong()
                when (schedule.frequency.unit) {
                    FrequencyUnit.DAY -> due.minusDays(count)
                    FrequencyUnit.WEEK -> due.minusDays(Math.multiplyExact(count, 7))
                    FrequencyUnit.MONTH -> anchor.yearMonth.minusMonths(count).let {
                        it.atDay(minOf(anchor.day, it.lengthOfMonth()))
                    }
                    FrequencyUnit.YEAR -> anchor.yearMonth.minusYears(count).let {
                        it.atDay(minOf(anchor.day, it.lengthOfMonth()))
                    }
                }
            }
        } catch (_: DateTimeException) { null
        } catch (_: ArithmeticException) { null
        } catch (_: CalendarCalculationException) { null }
    }

    private fun validate(schedule: RecurringSchedule) { regularDate(schedule, 0) }

    /** At most 63 probes, regardless of history length. Out-of-domain future dates act as +infinity. */
    private fun lowerBound(schedule: RecurringSchedule, from: LocalDate): Long {
        var low = 0L
        var high = Long.MAX_VALUE
        while (low < high) {
            val mid = low + (high - low) / 2
            val date = regularDateOrNull(schedule, mid)
            if (date == null || date >= from) high = mid else low = mid + 1
        }
        return low
    }

    private fun regularDateOrNull(schedule: RecurringSchedule, index: Long): LocalDate? = try {
        regularDate(schedule, index)
    } catch (_: CalendarCalculationException) {
        null
    }

    private fun regularDate(schedule: RecurringSchedule, index: Long): LocalDate {
        require(index >= 0) { "Cycle index must not be negative" }
        try {
            val offset = Math.multiplyExact(index, schedule.frequency.count.toLong())
            val anchor = schedule.anchor
            return when (schedule.frequency.unit) {
                FrequencyUnit.DAY -> anchor.yearMonth.atDay(anchor.day).plusDays(offset)
                FrequencyUnit.WEEK -> anchor.yearMonth.atDay(anchor.day).plusDays(Math.multiplyExact(offset, 7))
                FrequencyUnit.MONTH -> anchor.yearMonth.plusMonths(offset).let { month ->
                    month.atDay(minOf(anchor.day, month.lengthOfMonth()))
                }
                FrequencyUnit.YEAR -> {
                    val year = Math.toIntExact(Math.addExact(anchor.yearMonth.year.toLong(), offset))
                    val month = YearMonth.of(year, anchor.yearMonth.month)
                    month.atDay(minOf(anchor.day, month.lengthOfMonth()))
                }
            }
        } catch (error: ArithmeticException) {
            throw CalendarCalculationException("Cycle arithmetic overflow at index $index", error)
        } catch (error: DateTimeException) {
            throw CalendarCalculationException("Cycle date is outside LocalDate range at index $index", error)
        }
    }
}
