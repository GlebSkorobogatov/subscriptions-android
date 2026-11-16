package com.glebskorobogatov.subscriptiontracker.domain.calendar

import com.glebskorobogatov.subscriptiontracker.domain.model.Frequency
import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import java.time.LocalDate
import java.time.YearMonth

/** Retains the desired day even when the initial month is shorter (e.g. February, day 31). */
data class RenewalAnchor(val yearMonth: YearMonth, val day: Int) {
    init {
        require(day in 1..31) { "Anchor day must be in 1..31" }
    }

    companion object {
        fun from(date: LocalDate): RenewalAnchor = RenewalAnchor(YearMonth.from(date), date.dayOfMonth)
    }
}

data class CycleOverride(val cycleIndex: Long, val date: LocalDate) {
    init {
        require(cycleIndex >= 0) { "Cycle index must not be negative" }
    }
}

/** A schedule segment, not a collection of confirmed payments or historical records. */
data class RenewalSchedule(
    val anchor: RenewalAnchor,
    val frequency: Frequency,
    val nextCycleIndex: Long = 0,
    val oneOff: CycleOverride? = null,
) {
    init {
        require(nextCycleIndex >= 0) { "Next cycle index must not be negative" }
        require(oneOff == null || oneOff.cycleIndex == nextCycleIndex) {
            "Only the next unresolved cycle may have an override"
        }
        when (frequency.unit) {
            FrequencyUnit.DAY, FrequencyUnit.WEEK -> anchor.yearMonth.atDay(anchor.day)
            FrequencyUnit.YEAR -> require(anchor.day <= anchor.yearMonth.month.maxLength()) {
                "Invalid annual anchor day"
            }
            FrequencyUnit.MONTH -> Unit
        }
    }
}
