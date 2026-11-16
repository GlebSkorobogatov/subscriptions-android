package com.glebskorobogatov.subscriptiontracker.ui.history

import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.integration.PaymentHistoryItem
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.PaymentSource
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID

/** UI saved state only: preserve complete optimistic snapshots, including original requestPayload. */
internal object HistorySnapshots {
    private fun pack(vararg values: String?) = values.mapTo(arrayListOf()) { if(it == null) "0" else "1$it" }
    private fun unpack(values: List<String>) = values.map { if(it == "0") null else it.substring(1) }
    fun payment(p: PaymentEntity) = pack(p.id.toString(),p.subscriptionId.toString(),p.operationId.toString(),p.paidOn.toString(),
        p.amountMinor.toString(),p.currencyCode.value,p.recordedAt.toString(),p.source.name,p.requestPayload)
    fun payment(values: List<String>): PaymentEntity {
        val a = unpack(values)
        return PaymentEntity(UUID.fromString(a[0]),UUID.fromString(a[1]),UUID.fromString(a[2]),LocalDate.parse(a[3]),
            requireNotNull(a[4]).toLong(),CurrencyCode.of(requireNotNull(a[5])),Instant.parse(a[6]),PaymentSource.valueOf(requireNotNull(a[7])),a[8])
    }
    fun refund(r: RefundEntity) = pack(r.id.toString(),r.paymentId.toString(),r.refundedOn.toString(),r.amountMinor.toString(),r.currencyCode.value)
    fun refund(values: List<String>): RefundEntity {
        val a = unpack(values)
        return RefundEntity(UUID.fromString(a[0]),UUID.fromString(a[1]),LocalDate.parse(a[2]),requireNotNull(a[3]).toLong(),CurrencyCode.of(requireNotNull(a[4])))
    }
}

internal fun historyNewestFirst(items: List<PaymentHistoryItem>) = items.sortedWith(
    compareByDescending<PaymentHistoryItem> { it.payment.paidOn }.thenBy { it.payment.id.toString() })
internal fun refundsNewestFirst(items: List<RefundEntity>) = items.sortedWith(
    compareByDescending<RefundEntity> { it.refundedOn }.thenBy { it.id.toString() })
