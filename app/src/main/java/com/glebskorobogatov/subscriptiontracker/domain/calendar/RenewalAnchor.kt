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
