package com.glebskorobogatov.subscriptiontracker.ui.details

import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.payment.toDomain
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import java.time.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

internal fun detailsRow() = SubscriptionEntity(UUID(0,1),"Сервис","Личный",49900,CurrencyCode.of("RUB"),FrequencyUnit.MONTH,1,
    RenewalMode.AUTO,LifecycleState.ACTIVE,2027,1,31,0,null,null,null,null,null,null,null,Instant.EPOCH,Instant.EPOCH)
class PaymentAttemptTest {
    private val day = LocalDate.of(2027,1,31)
    private val operation = UUID(0,9).toString()
    private fun fields() = mapOf("paid" to day.toString(),"amount" to "499,00","currency" to "RUB","priceChoice" to "keep","next" to "2027-02-28")
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected validation error") } catch (_: Exception) { } }
    @Test fun snapshotRoundTripPreservesEveryColumnIncludingNullableValues() {
        val a = detailsRow().copy(name = "0|1",accountLabel = "",oneOffDueDate = day.minusDays(1),overrideCycleIndex = 0,
            currentPeriodStartOverride = day.minusDays(15),paidAccessEndDate = day.plusDays(5),cancellationLeadDays = 3,
            logoAssetId = UUID(0,3),catalogLogoId = "0",updatedAt = Instant.parse("2027-01-31T10:00:00.123Z"))
        assertEquals(a,PaymentAttempt.row(PaymentAttempt.snapshot(a)))
        assertEquals(detailsRow().copy(accountLabel = null),PaymentAttempt.row(PaymentAttempt.snapshot(detailsRow().copy(accountLabel = null))))
    }
    @Test fun ordinaryAutoNeverPassesSelectedNext() {
        val c = PaymentAttempt.command(detailsRow(),fields(),operation,day)
        assertNull(c.selectedNext); assertEquals(operation,c.operationId.toString())
    }
    @Test fun manualRequiresNextAfterActualDate() {
        val s = detailsRow().copy(renewalMode = RenewalMode.MANUAL)
        assertEquals(day.plusDays(28),PaymentAttempt.command(s,fields(),operation,day).selectedNext)
        rejects { PaymentAttempt.command(s,fields() + ("next" to day.toString()),operation,day) }
    }
    @Test fun paymentIssueRequiresNextEvenForAuto() {
        val s = detailsRow().copy(lifecycleState = LifecycleState.PAYMENT_ISSUE)
        assertNotNull(PaymentAttempt.command(s,fields(),operation,day).selectedNext)
        rejects { PaymentAttempt.command(s,fields() + ("next" to ""),operation,day) }
    }
    @Test fun actualFutureDateIsRejected() { rejects { PaymentAttempt.command(detailsRow(),fields() + ("paid" to "2027-02-01"),operation,day) } }
    @Test fun foreignCurrencyIsRejected() { rejects { PaymentAttempt.command(detailsRow(),fields() + ("currency" to "USD"),operation,day) } }
    @Test fun changedAmountRequiresExplicitPriceDecision() {
        rejects { PaymentAttempt.command(detailsRow(),fields() + mapOf("amount" to "599","priceChoice" to ""),operation,day) }
        assertFalse(PaymentAttempt.command(detailsRow(),fields() + ("amount" to "599"),operation,day).updateFuturePrice)
        assertTrue(PaymentAttempt.command(detailsRow(),fields() + mapOf("amount" to "599","priceChoice" to "update"),operation,day).updateFuturePrice)
    }
    @Test fun restoredSnapshotProducesIdenticalRequestPayload() {
        val s = detailsRow().copy(renewalMode = RenewalMode.MANUAL)
        val first = PaymentAttempt.command(s,fields(),operation,day)
        val restored = PaymentAttempt.command(PaymentAttempt.row(PaymentAttempt.snapshot(s)),fields(),operation,day.plusDays(2))
        assertEquals(first,restored); assertEquals(first.payload(),restored.payload())
    }
    @Test fun explicitFutureSeriesAllowsLongOverdueManualPayment() {
        val s = detailsRow().copy(renewalMode = RenewalMode.MANUAL,anchorYear = 2026,anchorMonth = 7)
        val command = PaymentAttempt.command(s,fields() + ("reanchor" to "true"),operation,day)
        val result = com.glebskorobogatov.subscriptiontracker.domain.payment.PaymentRules().apply(
            s.toDomain(),command,day)
        assertNotNull(result.paymentIntent)
    }
    @Test fun legacyPayloadRemainsByteCompatibleAndSeriesChoiceChangesIdentity() {
        val normal = PaymentAttempt.command(detailsRow(),fields(),operation,day)
        assertEquals("v1|00000000-0000-0000-0000-000000000001|2027-01-31|49900|RUB|CONFIRMED_RENEWAL|false|AUTO|~|2027-01|31|MONTH|1|0|~|~",normal.payload())
        assertNotEquals(normal.payload(),normal.copy(reanchor = true).payload())
    }
}
