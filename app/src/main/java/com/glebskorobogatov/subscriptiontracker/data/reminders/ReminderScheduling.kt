package com.glebskorobogatov.subscriptiontracker.data.reminders

import com.glebskorobogatov.subscriptiontracker.domain.reminders.*
import java.security.MessageDigest
import java.time.*
import java.util.UUID

/** Money is never involved in these infrastructure identifiers. */
object ReminderScheduling {
    const val VERSION = 1
    const val MAX_FAILURES = 3
    val lease: Duration = Duration.ofMinutes(5)
    fun identity(generation: UUID, subscription: UUID) = "reminder:v1:$generation:$subscription"
    fun fingerprint(events: List<PlannedReminder>, zone: ZoneId): String {
        val canonical = events.distinct().map { "${it.key.stableId}|${it.plannedLocalDateTime}" }.sorted()
        return MessageDigest.getInstance("SHA-256").digest(("global-renewals:3|$zone|" + canonical.joinToString("\n")).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    fun workName(generation: UUID, subscription: UUID, fingerprint: String, at: Instant): String {
        require(fingerprint.matches(Regex("[a-f0-9]{64}")))
        return "${identity(generation, subscription)}:$fingerprint:$at"
    }
    fun nextWake(now: Instant, zone: ZoneId): Instant = ReminderRules().resolveLocal(
        now.atZone(zone).toLocalDate().plusDays(1).atTime(0, 5), zone).toInstant()
    fun next(events: List<PlannedReminder>, now: Instant, zone: ZoneId, recover: Boolean): Instant? =
        events.map { ReminderRules().resolveLocal(it.plannedLocalDateTime, zone).toInstant() }
            .filter { recover || it > now }.minOrNull()
}

data class NotificationIdentity(val tag: String, val id: Int = 0)
enum class PublicationResult { PUBLISHED, UNAVAILABLE, RETRYABLE_FAILURE, PERMANENT_SKIP }
/** Synchronous, short, local publication only. No network/long IO; called under final freshness guard.
 * F-03 must reuse notification identity on replay: notify and Room cannot be atomic. */
fun interface ReminderPublisher {
    /** F-03 may expose permission/channel readiness without changing scheduling. */
    val available: Boolean get() = true
    fun publish(identity: NotificationIdentity, message: ReminderMessage): PublicationResult
    /** Fresh presentation, bound in the same guarded transaction as the final schedule check. */
    fun publish(identity: NotificationIdentity, message: ReminderMessage, presentation: ReminderPresentation): PublicationResult = publish(identity, message)
    /** No persistent shadow store: Android adapters inspect their own active notifications. */
    fun reconcilePublished(snapshot: PublishedReminderSnapshot) = Unit
}
object UnavailableReminderPublisher : ReminderPublisher {
    override val available = false
    override fun publish(identity: NotificationIdentity, message: ReminderMessage) = PublicationResult.UNAVAILABLE
}


data class ReminderPresentation(val name: String, val price: com.glebskorobogatov.subscriptiontracker.domain.model.Money)
data class PublishedReminderSnapshot(val generation: UUID, val subscriptions: List<ReminderSubscription>,
    val presentations: Map<UUID, ReminderPresentation>, val time: LocalTime, val now: Instant, val zone: ZoneId)
