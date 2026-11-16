package com.glebskorobogatov.subscriptiontracker.data.room

import androidx.room.*

data class PaymentWithRefunds(
    @Embedded val payment: PaymentEntity,
    @Relation(parentColumn = "id", entityColumn = "paymentId") val refunds: List<RefundEntity>,
)
data class SubscriptionHistory(
    @Embedded val subscription: SubscriptionEntity,
    @Relation(entity = PaymentEntity::class, parentColumn = "id", entityColumn = "subscriptionId")
    val payments: List<PaymentWithRefunds>,
    @Relation(parentColumn = "id", entityColumn = "subscriptionId") val reminders: List<ReminderOverrideEntity>,
)
