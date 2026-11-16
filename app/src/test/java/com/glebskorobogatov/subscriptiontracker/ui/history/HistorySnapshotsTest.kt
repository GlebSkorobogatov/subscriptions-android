package com.glebskorobogatov.subscriptiontracker.ui.history

import com.glebskorobogatov.subscriptiontracker.data.integration.PaymentHistoryItem
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.PaymentSource
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

internal fun historyPayment(id: Long = 1,date: LocalDate = LocalDate.of(2027,1,31)) = PaymentEntity(UUID(0,id),UUID(0,100),UUID(0,id+10),date,99900,CurrencyCode.of("RUB"),
    Instant.parse("2027-01-31T12:00:00.123Z"),PaymentSource.MANUAL_HISTORY,"v1|original|0|1")
class HistorySnapshotsTest {
    @Test fun completePaymentSnapshotRetainsIdentitySourceTimestampAndPayload() {
        for(source in PaymentSource.entries) for(payload in listOf(null,"","0","v1|original|0|1")) {
            val p = historyPayment().copy(source = source,requestPayload = payload)
            assertEquals(p,HistorySnapshots.payment(HistorySnapshots.payment(p)))
        }
    }
    @Test fun refundSnapshotRetainsParentCurrencyAndIdentity() {
        val r = RefundEntity(UUID(0,2),UUID(0,1),LocalDate.of(2027,1,31),1234,CurrencyCode.of("KWD"))
        assertEquals(r,HistorySnapshots.refund(HistorySnapshots.refund(r)))
    }
    @Test fun paymentsSortNewestFirstWithUuidTieBreak() {
        fun item(p: PaymentEntity) = PaymentHistoryItem(p,emptyList(),Money(p.amountMinor,p.currencyCode))
        val a = item(historyPayment(1)); val b = item(historyPayment(2)); val old = item(historyPayment(3,LocalDate.of(2026,12,31)))
        assertEquals(listOf(a,b,old),historyNewestFirst(listOf(old,b,a)))
    }
    @Test fun refundsSortDeterministicallyInsideTheirPayment() {
        val a = RefundEntity(UUID(0,1),UUID(0,10),LocalDate.of(2027,1,31),100,CurrencyCode.of("JPY"))
        val b = a.copy(id = UUID(0,2)); val old = a.copy(id = UUID(0,3),refundedOn = a.refundedOn.minusDays(1))
        assertEquals(listOf(a,b,old),refundsNewestFirst(listOf(old,b,a)))
    }
}
