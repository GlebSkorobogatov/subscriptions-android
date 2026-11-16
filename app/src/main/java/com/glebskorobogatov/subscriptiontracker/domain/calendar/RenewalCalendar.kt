package com.glebskorobogatov.subscriptiontracker.domain.calendar

import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

interface RenewalCalendar {
    /** Returns the effective date, including the single indexed override if present. */
    fun plannedCycleDate(schedule: RenewalSchedule, index: Long): LocalDate
    fun upcomingEvents(schedule: RenewalSchedule, from: LocalDate, untilExclusive: LocalDate): Sequence<LocalDate>
}

class CalendarCalculationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class CycleConflictException(
    val cycleIndex: Long,
    val proposedDate: LocalDate,
    val previousDate: LocalDate?,
    val nextDate: LocalDate?,
) : IllegalArgumentException("Override must be strictly between adjacent regular cycles")

/** Pure calendar arithmetic. No clock, zone, subscription state or persistence dependency. */
class DefaultRenewalCalendar(private val maxEventsPerQuery: Int = 10_000) : RenewalCalendar {
    init {
        require(maxEventsPerQuery > 0) { "Event limit must be positive" }
    }

    override fun plannedCycleDate(schedule: RenewalSchedule, index: Long): LocalDate {
        require(index >= 0) { "Cycle index must not be negative" }
        validate(schedule)
        return effectiveDate(schedule, index)
    }

    fun nextScheduledDate(schedule: RenewalSchedule): LocalDate = plannedCycleDate(schedule, schedule.nextCycleIndex)

    fun moveNextOnly(schedule: RenewalSchedule, date: LocalDate): RenewalSchedule =
        schedule.copy(oneOff = CycleOverride(schedule.nextCycleIndex, date)).also(::validate)

    /** New future segment; keep the old segment/history separately. Never mutates the input. */
    fun moveFutureSeries(schedule: RenewalSchedule, firstDate: LocalDate): RenewalSchedule {
        validate(schedule)
        return RenewalSchedule(RenewalAnchor.from(firstDate), schedule.frequency).also(::validate)
    }

    /** Inclusive lookup; unlike a half-open range it can also return LocalDate.MAX. */
    fun firstOnOrAfter(schedule: RenewalSchedule, from: LocalDate): LocalDate? {
        validate(schedule)
        return effectiveDateOrNull(schedule, lowerBound(schedule, from))
    }

    /** Indexed counterpart of firstOnOrAfter; uses the same bounded binary search. */
    fun firstCycleIndexOnOrAfter(schedule: RenewalSchedule, from: LocalDate): Long? {
        validate(schedule)
        val index = lowerBound(schedule, from)
        return index.takeIf { effectiveDateOrNull(schedule, it) != null }
    }

    override fun upcomingEvents(
        schedule: RenewalSchedule,
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
                val date = effectiveDateOrNull(schedule, index) ?: break
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
     * Target schedules have no payment index or one-off overrides; legacy callers retain their API.
     */
    fun previousRegularBoundary(schedule: RenewalSchedule, due: LocalDate): LocalDate? {
        require(schedule.oneOff == null) { "A regular period cannot contain a one-off override" }
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

    private fun validate(schedule: RenewalSchedule) {
        // A schedule cannot start at an unrepresentable index, even with an overriding date.
        regularDate(schedule, schedule.nextCycleIndex)
        val override = schedule.oneOff ?: return
        val previous = if (override.cycleIndex > 0) regularDate(schedule, override.cycleIndex - 1) else null
        val next = regularDateOrNull(schedule, Math.incrementExact(override.cycleIndex))
        if ((previous != null && override.date <= previous) || (next != null && override.date >= next)) {
            throw CycleConflictException(override.cycleIndex, override.date, previous, next)
        }
    }

    private fun effectiveDate(schedule: RenewalSchedule, index: Long): LocalDate =
        schedule.oneOff?.takeIf { it.cycleIndex == index }?.date ?: regularDate(schedule, index)

    private fun effectiveDateOrNull(schedule: RenewalSchedule, index: Long): LocalDate? =
        schedule.oneOff?.takeIf { it.cycleIndex == index }?.date ?: regularDateOrNull(schedule, index)

    /** At most 63 probes, regardless of history length. Out-of-domain future dates act as +infinity. */
    private fun lowerBound(schedule: RenewalSchedule, from: LocalDate): Long {
        var low = schedule.nextCycleIndex
        var high = Long.MAX_VALUE
        while (low < high) {
            val mid = low + (high - low) / 2
            val date = effectiveDateOrNull(schedule, mid)
            if (date == null || date >= from) high = mid else low = mid + 1
        }
        return low
    }

    private fun regularDateOrNull(schedule: RenewalSchedule, index: Long): LocalDate? = try {
        regularDate(schedule, index)
    } catch (_: CalendarCalculationException) {
        null
    }

    private fun regularDate(schedule: RenewalSchedule, index: Long): LocalDate {
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
