package com.glebskorobogatov.subscriptiontracker.domain.time

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

interface DateProvider {
    fun today(): LocalDate
    fun zoneId(): ZoneId
}

/** Inject Clock.fixed in tests; the application chooses its clock at the composition root. */
class ClockDateProvider(private val clock: Clock) : DateProvider {
    override fun today(): LocalDate = LocalDate.now(clock)
    override fun zoneId(): ZoneId = clock.zone
}
