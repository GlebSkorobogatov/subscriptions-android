package com.glebskorobogatov.subscriptiontracker.domain.model

import java.time.LocalDate

/** Nonempty local calendar range [startInclusive, endExclusive), independent of time zone. */
data class CalendarPeriod(val startInclusive: LocalDate, val endExclusive: LocalDate) {
    init {
        require(startInclusive < endExclusive) { "End must be after start" }
    }

    operator fun contains(date: LocalDate): Boolean = date >= startInclusive && date < endExclusive
}
