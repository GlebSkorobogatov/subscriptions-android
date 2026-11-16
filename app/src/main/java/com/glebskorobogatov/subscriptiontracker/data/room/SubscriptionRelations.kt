package com.glebskorobogatov.subscriptiontracker.data.room

import androidx.room.*

/** Read model only; no table/schema changes. @Transaction on the DAO keeps both parts coherent. */
data class SubscriptionWithReminders(
    @Embedded val subscription: SubscriptionEntity,
    @Relation(parentColumn = "id", entityColumn = "subscriptionId") val reminders: List<ReminderOverrideEntity>,
)
