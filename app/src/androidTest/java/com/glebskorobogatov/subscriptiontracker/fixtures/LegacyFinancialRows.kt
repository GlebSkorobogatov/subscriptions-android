package com.glebskorobogatov.subscriptiontracker.fixtures

import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.PaymentSource
import com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*

/** Direct synthetic v4 fixtures, not application operations. No payment/refund business service. */
suspend fun legacyPayment(db: TrackerDatabase, s: SubscriptionEntity, day: LocalDate, amount: Long = 999,
    currency: CurrencyCode = s.currencyCode): PaymentEntity = withContext(Dispatchers.IO) {
    PaymentEntity(UUID.randomUUID(), s.id, UUID.randomUUID(), day, amount, currency, Instant.EPOCH,
        PaymentSource.MANUAL_HISTORY).also { db.payments().insert(it) }
}
suspend fun legacyRefund(db: TrackerDatabase, p: PaymentEntity, day: LocalDate, amount: Long = 100): RefundEntity =
    withContext(Dispatchers.IO) { RefundEntity(UUID.randomUUID(), p.id, day, amount, p.currencyCode).also { db.refunds().insert(it) } }
