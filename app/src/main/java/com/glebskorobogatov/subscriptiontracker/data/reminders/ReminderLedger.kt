package com.glebskorobogatov.subscriptiontracker.data.reminders

import com.glebskorobogatov.subscriptiontracker.data.integration.reminderInputs
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.Settings
import com.glebskorobogatov.subscriptiontracker.domain.model.SubscriptionId
import com.glebskorobogatov.subscriptiontracker.domain.reminders.*
import java.time.*
import java.util.UUID
import java.util.concurrent.Callable
import com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderEventType as DomainEvent

/** All methods run off-main. SQLite transactions protect across independent Room connections. */
class ReminderLedger(private val db: TrackerDatabase, val generation: UUID) {
    private val rules = ReminderRules()
    private fun <T> transaction(block: () -> T): T = db.runInTransaction(Callable { block() })
    private fun inputs(settings: Settings) = reminderInputs(db.subscriptions().all(), db.reminders().all(), settings)
    private fun rows() = db.deliveries().all().filter { it.generation == generation }
    private fun NotificationDeliveryEntity.event(): PlannedReminder {
        require(payloadVersion == ReminderScheduling.VERSION) { "Unsupported reminder payload" }
        val time = LocalTime.parse(requireNotNull(plannedLocalTime))
        return PlannedReminder(SubscriptionId(subscriptionId), DomainEvent.valueOf(eventType.name), eventDate,
            offsetDays, plannedLocalDate.atTime(time))
    }
    private fun NotificationDeliveryEntity.complete() = state == DeliveryState.DELIVERED || state == DeliveryState.SKIPPED
    private fun NotificationDeliveryEntity.live(now: Instant) = state == DeliveryState.PROCESSING &&
        leaseUntil != null && leaseUntil > now && leaseUntil <= now.plus(ReminderScheduling.lease)
    private fun row(event: PlannedReminder): NotificationDeliveryEntity = NotificationDeliveryEntity(
        UUID.nameUUIDFromBytes(("$generation|${event.key.stableId}").toByteArray(Charsets.UTF_8)),
        event.subscriptionId.value, ReminderEventType.valueOf(event.eventType.name), event.targetDate,
        event.offsetDays, event.plannedLocalDateTime.toLocalDate(), null, DeliveryState.PENDING, generation,
        event.plannedLocalDateTime.toLocalTime().toString(), ReminderScheduling.VERSION)

    /** Persist before enqueue. Keep actual missed occurrences; plan(now) cannot reconstruct them. */
    fun reconcile(settings: Settings, now: Instant, zone: ZoneId, publisher: ReminderPublisher? = null): List<NotificationDeliveryEntity> = transaction {
        val source = inputs(settings)
        publisher?.reconcilePublished(PublishedReminderSnapshot(generation, source,
            db.subscriptions().all().associate { it.id to ReminderPresentation(it.name,
                com.glebskorobogatov.subscriptiontracker.domain.model.Money(it.priceMinor, it.currencyCode)) },
            settings.reminderLocalTime, now, zone))
        val horizon = now.atZone(zone).toLocalDate().plusDays(7).atStartOfDay()
        val fresh = rules.plan(source, settings.reminderLocalTime, now, zone)
            .filter { it.plannedLocalDateTime < horizon }.associateBy { it.key }
        val remaining = fresh.toMutableMap()
        rows().forEach { old ->
            if (old.complete()) {
                // A completed logical key remains handled even if time/zone changes.
                remaining.remove(ReminderKey(SubscriptionId(old.subscriptionId), DomainEvent.valueOf(old.eventType.name), old.eventDate, old.offsetDays))
            } else {
                val event = old.event() // Corruption/version errors are errors, never empty success.
                val valid = rules.matchesCurrentSchedule(event, source, settings.reminderLocalTime, now, zone)
                if (!old.live(now)) {
                    if (!valid) db.deliveries().delete(old.id)
                    else db.deliveries().update(old.copy(state = DeliveryState.PENDING, claimToken = null, leaseUntil = null))
                }
                // Do not replace an owned claim. Its final transaction revalidates it.
                if (valid || old.live(now)) remaining.remove(event.key)
            }
        }
        remaining.values.forEach { db.deliveries().insert(row(it)) }
        rows().filter { !it.complete() }
    }

    fun events(rows: List<NotificationDeliveryEntity>) = rows.map { it.event() }
    data class Claim(val token: UUID, val message: ReminderMessage)

    fun claim(subscription: UUID, settings: Settings, now: Instant, zone: ZoneId): Claim? = transaction {
        val all = rows().filter { it.subscriptionId == subscription }
        if (all.any { it.live(now) }) return@transaction null
        val candidates = all.filter { !it.complete() && it.failureCount < ReminderScheduling.MAX_FAILURES &&
            (it.notBefore == null || it.notBefore <= now) }
        val message = rules.dueMessages(inputs(settings), settings.reminderLocalTime, events(candidates), now, zone)
            .singleOrNull() ?: return@transaction null
        val token = UUID.randomUUID()
        candidates.filter { it.event().key in message.coveredKeys }.forEach {
            db.deliveries().update(it.copy(state = DeliveryState.PROCESSING, claimToken = token,
                leaseUntil = now.plus(ReminderScheduling.lease)))
        }
        Claim(token, message)
    }

    /** Call within generation guard, while production writers share dataset gate.
     * Fresh Room transaction also fences deletion/schedule changes through another connection. */
    fun publish(claim: Claim, settings: Settings, now: Instant, zone: ZoneId,
        publisher: ReminderPublisher): PublicationResult? = transaction {
        val owned = rows().filter { it.claimToken == claim.token && it.state == DeliveryState.PROCESSING }
        if (owned.isEmpty() || owned.any { !it.live(now) }) return@transaction null
        val current = rules.dueMessages(inputs(settings), settings.reminderLocalTime, events(owned), now, zone).singleOrNull()
        if (current == null) {
            owned.forEach { db.deliveries().delete(it.id) }
            return@transaction null
        }
        val result = try {
            val row = requireNotNull(db.subscriptions().find(current.subscriptionId.value))
            publisher.publish(NotificationIdentity(ReminderScheduling.identity(generation, current.subscriptionId.value)), current,
                ReminderPresentation(row.name, com.glebskorobogatov.subscriptiontracker.domain.model.Money(row.priceMinor, row.currencyCode)))
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
          catch (_: Exception) { PublicationResult.RETRYABLE_FAILURE }
        owned.forEach { old ->
            if (old.event().key !in current.coveredKeys) db.deliveries().delete(old.id)
            else {
                val failures = old.failureCount + if (result == PublicationResult.RETRYABLE_FAILURE) 1 else 0
                db.deliveries().update(old.copy(
                    state = when (result) {
                        PublicationResult.PUBLISHED -> DeliveryState.DELIVERED
                        PublicationResult.PERMANENT_SKIP -> DeliveryState.SKIPPED
                        else -> DeliveryState.PENDING
                    },
                    deliveredAt = if (result == PublicationResult.PUBLISHED) now else null,
                    claimToken = null, leaseUntil = null, failureCount = failures,
                    notBefore = if (result == PublicationResult.RETRYABLE_FAILURE) now.plusSeconds(30L shl (failures - 1)) else null))
            }
        }
        result
    }

    fun release(token: UUID) = transaction {
        rows().filter { it.claimToken == token }.forEach {
            db.deliveries().update(it.copy(state = DeliveryState.PENDING, claimToken = null, leaseUntil = null))
        }
    }
}
