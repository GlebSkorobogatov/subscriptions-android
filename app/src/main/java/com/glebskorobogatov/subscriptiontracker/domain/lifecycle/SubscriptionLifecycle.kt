package com.glebskorobogatov.subscriptiontracker.domain.lifecycle

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class RenewalMode { AUTO, MANUAL }
enum class LifecycleState { ACTIVE, PAYMENT_ISSUE, CANCELLATION_SCHEDULED, ARCHIVED }
enum class VisibleStatus { ACTIVE, AWAITING_RENEWAL, PAYMENT_ISSUE, CANCELLATION_SCHEDULED, ARCHIVED }
/** Storage-only discriminator for historical v4 rows, removed with the tables in R1b.4. No payment command consumes it. */
enum class PaymentSource { CONFIRMED_RENEWAL, MANUAL_HISTORY }

data class Subscription(
    val id: SubscriptionId,
    val mode: RenewalMode,
    val schedule: RenewalSchedule,
    val price: Money,
    val state: LifecycleState = LifecycleState.ACTIVE,
    val paidAccessEnd: LocalDate? = null,
    val periodStartOverride: LocalDate? = null,
) {
    init {
        price.requirePositive()
        require(state != LifecycleState.PAYMENT_ISSUE || mode == RenewalMode.AUTO)
    }
}

data class LifecycleResult(val subscription: Subscription)
data class LifecycleView(val status: VisibleStatus, val expectedDate: LocalDate?, val remainingFraction: Double?)
class InvalidTransition(message: String) : IllegalArgumentException(message)

class SubscriptionLifecycleService(private val calendar: DefaultRenewalCalendar = DefaultRenewalCalendar()) {
    private fun allowed(condition: Boolean) {
        if (!condition) throw InvalidTransition("Command is not allowed in this subscription state")
    }

    fun view(s: Subscription, today: LocalDate): LifecycleView {
        val due = if (s.state == LifecycleState.ACTIVE) {
            if (s.mode == RenewalMode.AUTO) calendar.firstOnOrAfter(s.schedule, today)
            else calendar.nextScheduledDate(s.schedule)
        } else null
        val overdue = due != null && s.mode == RenewalMode.MANUAL && due < today
        val status = if (overdue) VisibleStatus.AWAITING_RENEWAL else VisibleStatus.valueOf(s.state.name)
        val end = if (s.state == LifecycleState.CANCELLATION_SCHEDULED) s.paidAccessEnd else due
        // Cancellation retains the last known period; it must not roll forward with today.
        val periodBoundary = if (s.state == LifecycleState.CANCELLATION_SCHEDULED && end != null)
            calendar.nextScheduledDate(s.schedule) else due
        val start = s.periodStartOverride ?: periodBoundary?.let { previousBoundary(s.schedule, it) }
        val progress = when {
            s.state == LifecycleState.ARCHIVED || s.state == LifecycleState.PAYMENT_ISSUE -> null
            overdue -> null
            end == null || start == null || start >= end -> null
            else -> (ChronoUnit.DAYS.between(today, end).toDouble() /
                ChronoUnit.DAYS.between(start, end).toDouble()).coerceIn(0.0, 1.0)
        }
        return LifecycleView(status, due?.takeUnless { overdue }, progress)
    }

    private fun previousBoundary(schedule: RenewalSchedule, due: LocalDate): LocalDate? {
        var low = schedule.nextCycleIndex
        var high = Long.MAX_VALUE
        while (low < high) {
            val mid = low + (high - low) / 2
            val date = try { calendar.plannedCycleDate(schedule, mid) } catch (_: CalendarCalculationException) { null }
            if (date == null || date >= due) high = mid else low = mid + 1
        }
        return if (low == 0L) null else calendar.plannedCycleDate(schedule, low - 1)
    }

    /** Legacy-only recovery of an existing PAYMENT_ISSUE row; no action creates that state. Removed in R1b.4. */
    fun resolveWithDate(s: Subscription, date: LocalDate, reanchor: Boolean = false): LifecycleResult {
        allowed(s.state == LifecycleState.PAYMENT_ISSUE)
        val next = if (reanchor) calendar.moveFutureSeries(s.schedule, date) else calendar.moveNextOnly(s.schedule, date)
        return LifecycleResult(s.copy(state = LifecycleState.ACTIVE, schedule = next, periodStartOverride = null))
    }

    fun cancelRenewal(s: Subscription, paidAccessEnd: LocalDate? = s.paidAccessEnd): LifecycleResult {
        allowed(s.state == LifecycleState.ACTIVE || s.state == LifecycleState.PAYMENT_ISSUE)
        return LifecycleResult(s.copy(state = LifecycleState.CANCELLATION_SCHEDULED, paidAccessEnd = paidAccessEnd))
    }

    fun archive(s: Subscription): LifecycleResult {
        allowed(s.state != LifecycleState.ARCHIVED)
        return LifecycleResult(s.copy(state = LifecycleState.ARCHIVED))
    }

    fun reactivate(s: Subscription, mode: RenewalMode, schedule: RenewalSchedule, price: Money): LifecycleResult {
        allowed(s.state == LifecycleState.ARCHIVED)
        calendar.nextScheduledDate(schedule)
        return LifecycleResult(s.copy(mode = mode, schedule = schedule, price = price,
            state = LifecycleState.ACTIVE, paidAccessEnd = null, periodStartOverride = null))
    }
}
