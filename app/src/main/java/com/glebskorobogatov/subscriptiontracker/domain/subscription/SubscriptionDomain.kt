package com.glebskorobogatov.subscriptiontracker.domain.subscription

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit.DAYS

/** An explicit schedule edit distinguishes user intent from an unchanged displayed clamped date. */
data class ScheduleEdit(val firstDue: LocalDate, val frequency: Frequency, val anchorDay: Int? = null)
data class SubscriptionPeriod(val due: LocalDate?, val remainingDays: Long?, val remainingFraction: Double?)

/** Persistence must execute this request atomically with ledger/work invalidation and safe asset cleanup. */
data class SubscriptionDeletion(val subscriptionId: SubscriptionId)

/** Pure target contract shared by persistence, presentation, forecasts and reminders. */
class SubscriptionDomain(private val calendar: DefaultRenewalCalendar = DefaultRenewalCalendar()) {
    fun create(id: SubscriptionId, name: String, price: RubPrice, schedule: ScheduleEdit, today: LocalDate,
        accountLabel: String? = null, appearance: SubscriptionAppearance = SubscriptionAppearance(),
        periodStart: PeriodStart? = null): SubscriptionRecord {
        val series = validatedSchedule(schedule, today)
        validatePeriodStart(series, periodStart, today)
        return SubscriptionRecord(id, name, price, series, accountLabel, appearance, periodStart = periodStart)
    }

    /** Null scheduleEdit preserves the exact anchor. A period override is cleared on reanchor. */
    fun edit(s: SubscriptionRecord, today: LocalDate, name: String = s.name, price: RubPrice = s.price,
        accountLabel: String? = s.accountLabel, appearance: SubscriptionAppearance = s.appearance,
        scheduleEdit: ScheduleEdit? = null, periodStart: PeriodStart? = if (scheduleEdit == null) s.periodStart else null,
    ): SubscriptionRecord {
        val series = scheduleEdit?.let { validatedSchedule(it, today) } ?: s.schedule
        // Expired overrides are harmless when unrelated fields are edited, never reused for a new period.
        if (periodStart != s.periodStart || scheduleEdit != null) validatePeriodStart(series, periodStart, today)
        return s.copy(name = name, price = price, accountLabel = accountLabel, appearance = appearance,
            schedule = series, periodStart = periodStart)
    }

    fun nextDue(schedule: RecurringSchedule, today: LocalDate): LocalDate =
        calendar.firstOnOrAfter(schedule, today)
            ?: throw CalendarCalculationException("No representable next renewal date")

    private fun validatedSchedule(edit: ScheduleEdit, today: LocalDate): RecurringSchedule {
        require(edit.firstDue >= today) { "Next renewal must not precede today" }
        return RecurringSchedule.starting(edit.firstDue, edit.frequency, edit.anchorDay)
    }

    private fun validatePeriodStart(schedule: RecurringSchedule, override: PeriodStart?, today: LocalDate) {
        if (override == null) return
        require(override.due == nextDue(schedule, today) && override.start < override.due && override.start <= today) {
            "Period start must describe the current period"
        }
    }

    private fun start(s: SubscriptionRecord, due: LocalDate): LocalDate? =
        s.periodStart?.takeIf { it.due == due }?.start
            ?: calendar.previousRegularBoundary(s.schedule, due)

    fun period(s: SubscriptionRecord, today: LocalDate): SubscriptionPeriod {
        if (s.state == SubscriptionState.ARCHIVED) return SubscriptionPeriod(null, null, null)
        val due = if (s.state == SubscriptionState.ACTIVE) nextDue(s.schedule, today) else s.cancelledAccessEndExclusive
        val start = if (s.state == SubscriptionState.ACTIVE && due != null) start(s, due) else s.frozenCancelledPeriodStart
        val fraction = when {
            due == null || start == null || start >= due -> null
            s.state == SubscriptionState.CANCELLED && today >= due -> null
            else -> (DAYS.between(today, due).toDouble() / DAYS.between(start, due)).coerceIn(0.0, 1.0)
        }
        return SubscriptionPeriod(due, due?.let { DAYS.between(today, it) }, fraction)
    }

    fun cancel(s: SubscriptionRecord, today: LocalDate, endExclusive: LocalDate = nextDue(s.schedule, today)): SubscriptionRecord {
        require(s.state == SubscriptionState.ACTIVE)
        val due = nextDue(s.schedule, today)
        return s.copy(state = SubscriptionState.CANCELLED, cancelledAccessEndExclusive = endExclusive,
            frozenCancelledPeriodStart = start(s, due), periodStart = null)
    }

    fun editEnd(s: SubscriptionRecord, endExclusive: LocalDate): SubscriptionRecord {
        require(s.state == SubscriptionState.CANCELLED)
        return s.copy(cancelledAccessEndExclusive = endExclusive)
    }

    fun archive(s: SubscriptionRecord, today: LocalDate, confirmed: Boolean): SubscriptionRecord {
        require(s.state != SubscriptionState.ARCHIVED)
        return if (confirmed) s.copy(state = SubscriptionState.ARCHIVED, archivedAt = today) else s
    }

    fun reactivate(s: SubscriptionRecord, price: RubPrice, schedule: ScheduleEdit, today: LocalDate): SubscriptionRecord {
        require(s.state != SubscriptionState.ACTIVE)
        return s.copy(state = SubscriptionState.ACTIVE, price = price, schedule = validatedSchedule(schedule, today),
            cancelledAccessEndExclusive = null, frozenCancelledPeriodStart = null, periodStart = null, archivedAt = null)
    }

    fun delete(s: SubscriptionRecord, confirmed: Boolean): SubscriptionDeletion? =
        if (confirmed) SubscriptionDeletion(s.id) else null

    /** Evaluate dates once per snapshot, not on every comparator invocation. */
    fun sortedMain(rows: List<SubscriptionRecord>, today: LocalDate): List<SubscriptionRecord> = rows
        .filter { it.state != SubscriptionState.ARCHIVED }
        .map { it to if (it.participatesInCalculations) nextDue(it.schedule, today) else it.cancelledAccessEndExclusive }
        .sortedWith(compareBy<Pair<SubscriptionRecord, LocalDate?>> { it.first.state.ordinal }
            .thenBy(nullsLast()) { it.second }.thenBy { it.first.id.toString() }).map { it.first }

    fun sortedArchive(rows: List<SubscriptionRecord>): List<SubscriptionRecord> = rows
        .filter { it.state == SubscriptionState.ARCHIVED }
        .sortedWith(compareBy<SubscriptionRecord, LocalDate?>(nullsLast(reverseOrder())) { it.archivedAt }
            .thenBy { it.id.toString() })
}
