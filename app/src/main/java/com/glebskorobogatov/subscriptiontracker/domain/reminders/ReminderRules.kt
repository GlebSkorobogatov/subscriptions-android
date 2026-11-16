package com.glebskorobogatov.subscriptiontracker.domain.reminders

import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.Subscription
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.SubscriptionLifecycleService
import com.glebskorobogatov.subscriptiontracker.domain.model.SubscriptionId
import java.time.*
import java.time.temporal.ChronoUnit

enum class ReminderEventType { PAYMENT, CANCELLATION }
private val allowedOffsets = setOf(7,3,1,0)

/** Already resolved by C-02 effectiveOffsets, independently for each event type. */
data class ReminderSubscription(val subscription: Subscription,val cancellationLeadDays: Int?,
    val paymentOffsets: Set<Int>,val cancellationOffsets: Set<Int>) {
    init { validate() }
    internal fun validate() {
        require(cancellationLeadDays == null || cancellationLeadDays >= 0)
        require(paymentOffsets.all { it in allowedOffsets } && cancellationOffsets.all { it in allowedOffsets })
    }
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

/** No clocks, storage, notifications or owned state. Only the nearest currently eligible renewal
 * is considered; AUTO advances by calendar view, MANUAL waits for explicit confirmation. */
class ReminderRules(private val lifecycle: SubscriptionLifecycleService = SubscriptionLifecycleService()) {
    private val order = compareBy<PlannedReminder> { it.plannedLocalDateTime }
        .thenBy { it.subscriptionId.toString() }.thenBy { it.eventType.name }
        .thenBy { it.targetDate }.thenBy { it.offsetDays }

    /** Future plan: never schedule a missed offset retrospectively (including earlier today).
     * Inclusive at now; F-02 must suppress handled keys using its persistent ledger. */
    fun plan(subscriptions: List<ReminderSubscription>,localTime: LocalTime,now: Instant,zone: ZoneId): List<PlannedReminder> = calculation {
        candidates(subscriptions,localTime,now.atZone(zone).toLocalDate())
            .filter { !resolveLocal(it.plannedLocalDateTime,zone).toInstant().isBefore(now) }
    }

    /** Snapshot freshness, not a delivery authorization: an occurrence may still require recovery
     * despite its original time being past. Normal scheduling must use plan(), recovery dueMessages(). */
    fun matchesCurrentSchedule(reminder: PlannedReminder,subscriptions: List<ReminderSubscription>,
        localTime: LocalTime,now: Instant,zone: ZoneId): Boolean = calculation {
        reminder in candidates(subscriptions,localTime,now.atZone(zone).toLocalDate())
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
        val current = candidates(subscriptions,localTime,today).toSet()
        previouslyPlanned.asSequence().filter { it in current && it.key !in handledKeys }
            .filter { !resolveLocal(it.plannedLocalDateTime,zone).toInstant().isAfter(now) }
            .distinct().sortedWith(order).toList().groupBy { it.subscriptionId }.entries
            .sortedBy { it.key.toString() }.map { (id,items) ->
                val reasons = items.groupBy { it.eventType to it.targetDate }.entries
                    .sortedWith(compareBy({ it.key.second },{ it.key.first.name })).map { (event,occurrences) ->
                        CurrentReminderReason(event.first,event.second,ChronoUnit.DAYS.between(today,event.second),occurrences)
                    }
                ReminderMessage(id,today,reasons)
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

    private fun candidates(inputs: List<ReminderSubscription>,time: LocalTime,today: LocalDate): List<PlannedReminder> {
        val unique = inputs.distinct()
        require(unique.map { it.subscription.id }.distinct().size == unique.size) { "Conflicting subscription snapshots" }
        return unique.flatMap { input ->
            input.validate()
            val due = lifecycle.view(input.subscription,today).expectedDate ?: return@flatMap emptyList()
            buildList {
                fun append(type: ReminderEventType,target: LocalDate,offsets: Set<Int>) {
                    if(target < today) return
                    offsets.forEach { offset ->
                        add(PlannedReminder(input.subscription.id,type,target,offset,target.minusDays(offset.toLong()).atTime(time)))
                    }
                }
                append(ReminderEventType.PAYMENT,due,input.paymentOffsets)
                input.cancellationLeadDays?.let { lead ->
                    if(input.cancellationOffsets.isNotEmpty()) append(ReminderEventType.CANCELLATION,due.minusDays(lead.toLong()),input.cancellationOffsets)
                }
            }
        }.distinct().sortedWith(order)
    }

    private inline fun <T> calculation(block: () -> T): T = try { block() }
        catch(error: DateTimeException) { throw ReminderCalculationException("Reminder date/time outside supported range",error) }
}
