package com.glebskorobogatov.subscriptiontracker.data.integration

import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import com.glebskorobogatov.subscriptiontracker.data.room.ReminderEventType
import com.glebskorobogatov.subscriptiontracker.data.room.ReminderOverrideEntity
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.settings.Settings
import com.glebskorobogatov.subscriptiontracker.data.settings.effectiveOffsets
import com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderSubscription

/** In-memory snapshot adapter only. No DAO, DataStore access or copied global overrides.
 * F-02 can feed existing repository snapshots here and pass settings.reminderLocalTime to the rules. */
fun reminderInputs(rows: List<SubscriptionEntity>,overrides: List<ReminderOverrideEntity>,settings: Settings): List<ReminderSubscription> {
    require(rows.map { it.id }.distinct().size == rows.size) { "Duplicate subscriptions" }
    val ids = rows.map { it.id }.toSet()
    require(overrides.all { it.subscriptionId in ids }) { "Override belongs to another subscription" }
    val indexed = overrides.associateBy { it.subscriptionId to it.eventType }
    require(indexed.size == overrides.size) { "Duplicate event overrides" }
    return rows.sortedBy { it.id.toString() }.map { row ->
        ReminderSubscription(row.toDomain(),row.cancellationLeadDays,
            effectiveOffsets(settings,ReminderEventType.PAYMENT,indexed[row.id to ReminderEventType.PAYMENT]),
            effectiveOffsets(settings,ReminderEventType.CANCELLATION,indexed[row.id to ReminderEventType.CANCELLATION]))
    }
}
