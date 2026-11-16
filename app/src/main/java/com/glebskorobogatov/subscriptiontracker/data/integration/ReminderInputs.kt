package com.glebskorobogatov.subscriptiontracker.data.integration

import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.settings.Settings
import com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderSubscription

/** No DAO for legacy overrides: only the global renewal preference is authoritative. */
fun reminderInputs(rows: List<SubscriptionEntity>, settings: Settings): List<ReminderSubscription> {
    require(rows.map { it.id }.distinct().size == rows.size) { "Duplicate subscriptions" }
    return rows.sortedBy { it.id.toString() }.map { ReminderSubscription(it.toDomain(), settings.paymentOffsetsDefault.toSet()) }
}
