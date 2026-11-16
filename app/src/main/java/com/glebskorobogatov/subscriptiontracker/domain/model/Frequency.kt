package com.glebskorobogatov.subscriptiontracker.domain.model

import java.time.Period

/** Calendar units, never approximated with a fixed number of seconds or 30-day months. */
enum class FrequencyUnit { DAY, WEEK, MONTH, YEAR }

data class Frequency(val unit: FrequencyUnit, val count: Int) {
    init {
        require(count > 0) { "Interval count must be positive" }
    }

    /** Creates a calendar quantity only; applying it to a renewal schedule belongs to B-02. */
    fun toPeriod(): Period = when (unit) {
        FrequencyUnit.DAY -> Period.ofDays(count)
        FrequencyUnit.WEEK -> Period.ofDays(Math.multiplyExact(count, 7))
        FrequencyUnit.MONTH -> Period.ofMonths(count)
        FrequencyUnit.YEAR -> Period.ofYears(count)
    }
}
