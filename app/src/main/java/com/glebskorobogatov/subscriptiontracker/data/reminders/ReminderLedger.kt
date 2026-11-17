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
    private fun inputs(settings: Settings) = reminderInputs(db.subscriptions().all(), settings)
    private fun rows(now: Instant, zone: ZoneId): List<NotificationDeliveryEntity> {
        val today = now.atZone(zone).toLocalDate()
        return db.deliveries().inWindow(generation,today,today.plusDays(ReminderRules.HORIZON_DAYS + 7))
    }
    companion object { const val CLEANUP_BATCH = 256 }

    /** Retain logical tombstones indefinitely: rolling the system date back must not redeliver.
     * Expired non-live attempts become SKIPPED (not DELIVERED); terminal retry/claim metadata is
     * compacted. No deletion can be proven safe without a durable watermark/tombstone schema.
     * One bounded batch per reconciliation; never infer no publication from an expired lease. */
    fun cleanup(now: Instant, zone: ZoneId): Int = transaction {
        val batch = db.deliveries().cleanupBatch(generation,now.atZone(zone).toLocalDate(),now,CLEANUP_BATCH)
        // ISO text filtering is only a coarse SQL prefilter (fractional seconds vary).
        val expired = batch.filter { it.leaseUntil == null || it.leaseUntil <= now }
        expired.forEach { old ->
            db.deliveries().update(old.copy(state = if (old.complete()) old.state else DeliveryState.SKIPPED,
                claimToken = null,leaseUntil = null,failureCount = 0,notBefore = null))
        }
        expired.size
    }
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
                com.glebskorobogatov.subscriptiontracker.domain.model.Money(it.priceMinor, com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode.of("RUB"))) },
            settings.reminderLocalTime, now, zone))
        cleanup(now,zone)
        val current = rules.currentCandidates(source,settings.reminderLocalTime,now.atZone(zone).toLocalDate())
        val validEvents = current.events.toHashSet()
        // Calendar errors are scoped to a series. No raw exception or private data is logged.
        val horizon = now.atZone(zone).toLocalDate().plusDays(ReminderRules.HORIZON_DAYS).atStartOfDay()
        val fresh = current.events.filter { it.plannedLocalDateTime < horizon &&
            rules.resolveLocal(it.plannedLocalDateTime,zone).toInstant() >= now }.associateBy { it.key }
        val remaining = fresh.toMutableMap()
        rows(now,zone).forEach { old ->
            val key = ReminderKey(SubscriptionId(old.subscriptionId), DomainEvent.RENEWAL, old.eventDate, old.offsetDays)
            if (old.complete()) remaining.remove(key)
            else {
                val event = old.event() // Invalid current payloads remain explicit errors.
                val valid = event in validEvents
                val replacement = remaining[key]
                if (!old.live(now)) {
                    when {
                        valid -> db.deliveries().update(old.copy(state = DeliveryState.PENDING, claimToken = null, leaseUntil = null))
                        replacement != null -> db.deliveries().update(old.copy(state = DeliveryState.PENDING,
                            plannedLocalDate = replacement.plannedLocalDateTime.toLocalDate(),
                            plannedLocalTime = replacement.plannedLocalDateTime.toLocalTime().toString(),
                            claimToken = null, leaseUntil = null))
                        else -> db.deliveries().update(old.copy(state = DeliveryState.PENDING,claimToken = null,leaseUntil = null))
                    }
                }
                // Inactive pending keys remain in the ledger and can become eligible only via current global rules.
                // Keep a live claim fenced; publication revalidates it against global rules.
                remaining.remove(key)
            }
        }
        remaining.values.forEach { db.deliveries().insert(row(it)) }
        rows(now,zone).filter { it.eventType == ReminderEventType.RENEWAL && !it.complete() &&
            (it.live(now) || it.event() in validEvents) }
    }

    fun events(rows: List<NotificationDeliveryEntity>) = rows.map { it.event() }
    data class Claim(val token: UUID, val message: ReminderMessage)

    fun claim(subscription: UUID, settings: Settings, now: Instant, zone: ZoneId): Claim? = transaction {
        val all = rows(now,zone).filter { it.subscriptionId == subscription && it.eventType == ReminderEventType.RENEWAL }
        if (db.deliveries().processing(generation,subscription).any { it.live(now) }) return@transaction null
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
        val owned = db.deliveries().claimed(claim.token).filter { it.generation == generation && it.state == DeliveryState.PROCESSING }
        if (owned.isEmpty() || owned.any { !it.live(now) }) return@transaction null
        val current = rules.dueMessages(inputs(settings), settings.reminderLocalTime, events(owned), now, zone).singleOrNull()
        if (current == null) {
            owned.forEach { db.deliveries().update(it.copy(state = DeliveryState.PENDING,claimToken = null,leaseUntil = null)) }
            return@transaction null
        }
        val result = try {
            val row = requireNotNull(db.subscriptions().find(current.subscriptionId.value))
            publisher.publish(NotificationIdentity(ReminderScheduling.identity(generation, current.subscriptionId.value)), current,
                ReminderPresentation(row.name, com.glebskorobogatov.subscriptiontracker.domain.model.Money(row.priceMinor, com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode.of("RUB"))))
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
          catch (_: Exception) { PublicationResult.RETRYABLE_FAILURE }
        owned.forEach { old ->
            if (old.event().key !in current.coveredKeys) db.deliveries().update(old.copy(state = DeliveryState.PENDING,claimToken = null,leaseUntil = null))
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
        if (result == PublicationResult.PUBLISHED) {
            // A successful recovery settles this backlog. Keys held back by retry metadata or
            // exhausted attempts are NOT falsely acknowledged as delivered, nor allowed to form
            // a later catch-up chain. Future occurrences and disabled offsets are untouched.
            val backlog = rows(now,zone).filter { it.subscriptionId == current.subscriptionId.value &&
                !it.complete() && it.claimToken == null }
            val suppressed = rules.dueMessages(inputs(settings),settings.reminderLocalTime,events(backlog),now,zone)
                .flatMap { it.coveredKeys }.toSet()
            backlog.filter { it.event().key in suppressed }.forEach {
                db.deliveries().update(it.copy(state = DeliveryState.SKIPPED,notBefore = null))
            }
        }
        result
    }

    fun release(token: UUID) = transaction {
        db.deliveries().claimed(token).filter { it.generation == generation }.forEach {
            db.deliveries().update(it.copy(state = DeliveryState.PENDING, claimToken = null, leaseUntil = null))
        }
    }
}
