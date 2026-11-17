package com.glebskorobogatov.subscriptiontracker.domain.reminders

import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.model.SubscriptionId
import java.time.*
import java.time.temporal.ChronoUnit

/** The only event kind in the fresh v5 delivery contract. */
enum class ReminderEventType { RENEWAL }
private val allowedOffsets = setOf(7,3,1,0)

/** Global renewal offsets shared by all active subscriptions. */
data class ReminderSubscription(val subscription: SubscriptionRecord, val renewalOffsets: Set<Int>) {
    init { validate() }
    internal fun validate() { require(renewalOffsets.all { it in allowedOffsets }) }
}

/** Logical identity v1: independent of configured time, zone, input order and delivery attempts.
 * Calendar cycles have distinct dates. Matches the existing ledger's subscription/type/date/offset contract.
 * Dataset generation must be added by F-02; this key alone is NOT cross-import/exactly-once protection. */
data class ReminderKey(val subscriptionId: SubscriptionId,val eventType: ReminderEventType,
    val targetDate: LocalDate,val offsetDays: Int) {
    init { require(offsetDays in allowedOffsets) }
    val stableId: String get() = "reminder:v1|$subscriptionId|${eventType.name}|$targetDate|$offsetDays"
}

data class PlannedReminder(val subscriptionId: SubscriptionId,val eventType: ReminderEventType,
    val targetDate: LocalDate,val offsetDays: Int,val plannedLocalDateTime: LocalDateTime) {
    init {
        require(offsetDays in allowedOffsets)
        require(plannedLocalDateTime.toLocalDate() == targetDate.minusDays(offsetDays.toLong()))
    }
    val key: ReminderKey get() = ReminderKey(subscriptionId,eventType,targetDate,offsetDays)
}

/** One current reason, even if several old offset occurrences were missed. Use daysRemaining/targetDate
 * for future presentation, NOT an obsolete "in N days" label from occurrences.offsetDays. */
data class CurrentReminderReason(val eventType: ReminderEventType,val targetDate: LocalDate,
    val daysRemaining: Long,val occurrences: List<PlannedReminder>)
data class ReminderMessage(val subscriptionId: SubscriptionId,val asOfLocalDate: LocalDate,
    val reasons: List<CurrentReminderReason>) {
    val coveredKeys: Set<ReminderKey> get() = reasons.flatMap { it.occurrences }.map { it.key }.toSet()
}
class ReminderCalculationException(message: String,cause: Throwable): IllegalArgumentException(message,cause)

/** Bounded projection, not a second calendar. Failed series never authorize delivery. */
data class ReminderCandidates(val events: List<PlannedReminder>, val failedSubscriptions: Set<SubscriptionId>)

/** No clocks, storage, notifications or owned state. All boundaries come from the shared calendar. */
class ReminderRules(private val calendar: DefaultRenewalCalendar = DefaultRenewalCalendar()) {
    companion object {
        const val HORIZON_DAYS = 7L
        // At most one series event/day. For a due >= today and a past reminder there are
        // sum(offset+1) = 8+4+2+1 = 15 logical occurrences, and at most 8 distinct due dates.
        const val MAX_MESSAGE_OCCURRENCES = 15
    }
    private val order = compareBy<PlannedReminder> { it.plannedLocalDateTime }
        .thenBy { it.subscriptionId.toString() }.thenBy { it.eventType.name }
        .thenBy { it.targetDate }.thenBy { it.offsetDays }

    /** Future plan: never schedule a missed offset retrospectively (including earlier today).
     * Inclusive at now; F-02 must suppress handled keys using its persistent ledger. */
    fun plan(subscriptions: List<ReminderSubscription>,localTime: LocalTime,now: Instant,zone: ZoneId): List<PlannedReminder> = calculation {
        currentCandidates(subscriptions,localTime,now.atZone(zone).toLocalDate()).events
            .filter { !resolveLocal(it.plannedLocalDateTime,zone).toInstant().isBefore(now) }
    }

    /** Snapshot freshness, not a delivery authorization: an occurrence may still require recovery
     * despite its original time being past. Normal scheduling must use plan(), recovery dueMessages(). */
    fun matchesCurrentSchedule(reminder: PlannedReminder,subscriptions: List<ReminderSubscription>,
        localTime: LocalTime,now: Instant,zone: ZoneId): Boolean = calculation {
        reminder in currentCandidates(subscriptions.filter { it.subscription.id == reminder.subscriptionId },
            localTime,now.atZone(zone).toLocalDate()).events
    }

    /** Explicitly previously planned occurrences only: empty input cannot invent a catch-up on creation.
     * Revalidate against current snapshots/time/settings; exclude handled keys and expired targets.
     * At most ONE message per subscription/check. Preserve all covered logical keys for F-02 to mark.
     * Pure handledKeys is only an input; this function records/delivers nothing and repeated checks
     * without durable bookkeeping will return the same message again. */
    fun dueMessages(subscriptions: List<ReminderSubscription>,localTime: LocalTime,
        previouslyPlanned: List<PlannedReminder>,now: Instant,zone: ZoneId,
        handledKeys: Set<ReminderKey> = emptySet()): List<ReminderMessage> = calculation {
        val today = now.atZone(zone).toLocalDate()
        val current = currentCandidates(subscriptions,localTime,today).events.toSet()
        previouslyPlanned.asSequence().filter { it in current && it.key !in handledKeys }
            .filter { !resolveLocal(it.plannedLocalDateTime,zone).toInstant().isAfter(now) }
            .distinct().sortedWith(order).toList().groupBy { it.subscriptionId }.entries
            .sortedBy { it.key.toString() }.map { (id,items) ->
                val reasons = items.groupBy { it.eventType to it.targetDate }.entries
                    .sortedWith(compareBy({ it.key.second },{ it.key.first.name })).map { (event,occurrences) ->
                        CurrentReminderReason(event.first,event.second,ChronoUnit.DAYS.between(today,event.second),occurrences)
                    }
                ReminderMessage(id,today,reasons).also { require(it.coveredKeys.size <= MAX_MESSAGE_OCCURRENCES) }

            }
    }

    /** Gap -> first valid local time AFTER the gap (Amsterdam 02:30 ->03:00, not03:30).
     * Overlap -> first valid offset from ZoneRules (earlier occurrence, Amsterdam summer +02:00).
     * The logical plannedLocalDateTime/targetDate/key are not modified by this conversion. */
    fun resolveLocal(local: LocalDateTime,zone: ZoneId): ZonedDateTime = calculation {
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        if(offsets.isNotEmpty()) ZonedDateTime.ofStrict(local,offsets.first(),zone)
        else {
            val transition = requireNotNull(rules.getTransition(local)) { "Missing zone transition" }
            ZonedDateTime.ofStrict(transition.dateTimeAfter,transition.offsetAfter,zone)
        }
    }

    /** Includes persisted missed occurrences as well as future ones. The upper boundary applies
     * to reminder dates. No event before the first series boundary can be invented. Mapping/read
     * errors are deliberately outside this calculation boundary; cancellation is never caught. */
    fun currentCandidates(inputs: List<ReminderSubscription>, time: LocalTime, today: LocalDate): ReminderCandidates = calculation {
        val unique = inputs.distinct()
        require(unique.map { it.subscription.id }.distinct().size == unique.size) { "Conflicting subscription snapshots" }
        val until = today.plusDays(HORIZON_DAYS)
        val failed = linkedSetOf<SubscriptionId>()
        val events = unique.flatMap { input ->
            input.validate()
            if (!input.subscription.participatesInCalculations || input.renewalOffsets.isEmpty()) return@flatMap emptyList()
            try {
                // Verify representability, even when the bounded range contains no event.
                if (calendar.firstOnOrAfter(input.subscription.schedule,today) == null)
                    throw CalendarCalculationException("No representable next renewal date")
                val dueUntil = until.plusDays(input.renewalOffsets.max().toLong())
                calendar.upcomingEvents(input.subscription.schedule,today,dueUntil).flatMap { due ->
                    input.renewalOffsets.asSequence().map { offset ->
                        PlannedReminder(input.subscription.id,ReminderEventType.RENEWAL,due,offset,
                            due.minusDays(offset.toLong()).atTime(time))
                    }
                }.filter { it.plannedLocalDateTime.toLocalDate() < until }.toList()
            } catch (_: CalendarCalculationException) {
                failed += input.subscription.id; emptyList()
            } catch (_: DateTimeException) {
                failed += input.subscription.id; emptyList()
            } catch (_: ArithmeticException) {
                failed += input.subscription.id; emptyList()
            }
        }.distinct().sortedWith(order)
        ReminderCandidates(events,failed)
    }

    /** Permission context is independent of whether an occurrence falls in this week's horizon. */
    fun eligibleForPermission(input: ReminderSubscription, today: LocalDate): Boolean =
        input.subscription.participatesInCalculations && input.renewalOffsets.isNotEmpty() &&
            calendar.firstOnOrAfter(input.subscription.schedule,today) != null

    private inline fun <T> calculation(block: () -> T): T = try { block() }
        catch(error: DateTimeException) { throw ReminderCalculationException("Reminder date/time outside supported range",error) }
}
