package com.glebskorobogatov.subscriptiontracker.ui.details

import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.payment.toDomain
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.payment.*
import java.time.*
import java.util.UUID

/** SavedStateHandle-compatible scalar snapshot. Preserve the FULL expected row and exact payload
 * across process recreation; a retry must never silently use the advanced subscription snapshot. */
internal object PaymentAttempt {
    fun snapshot(s: SubscriptionEntity): ArrayList<String> = arrayListOf<String?> (
        s.id.toString(),s.name,s.accountLabel,s.priceMinor.toString(),s.currencyCode.value,s.intervalUnit.name,
        s.intervalCount.toString(),s.renewalMode.name,s.lifecycleState.name,s.anchorYear.toString(),s.anchorMonth.toString(),
        s.anchorDay.toString(),s.nextCycleIndex.toString(),s.oneOffDueDate?.toString(),s.overrideCycleIndex?.toString(),
        s.currentPeriodStartOverride?.toString(),s.paidAccessEndDate?.toString(),s.cancellationLeadDays?.toString(),
        s.logoAssetId?.toString(),s.catalogLogoId,s.createdAt.toString(),s.updatedAt.toString()
    ).mapTo(arrayListOf()) { if (it == null) "0" else "1$it" }
    fun row(values: List<String>): SubscriptionEntity {
        val a = values.map { if (it == "0") null else it.substring(1) }
        fun v(i: Int) = requireNotNull(a[i])
        return SubscriptionEntity(UUID.fromString(v(0)),v(1),a[2],v(3).toLong(),CurrencyCode.of(v(4)),
            FrequencyUnit.valueOf(v(5)),v(6).toInt(),RenewalMode.valueOf(v(7)),LifecycleState.valueOf(v(8)),
            v(9).toInt(),v(10).toInt(),v(11).toInt(),v(12).toLong(),a[13]?.let(LocalDate::parse),a[14]?.toLong(),
            a[15]?.let(LocalDate::parse),a[16]?.let(LocalDate::parse),a[17]?.toInt(),a[18]?.let(UUID::fromString),a[19],
            Instant.parse(v(20)),Instant.parse(v(21)))
    }
    fun command(s: SubscriptionEntity, fields: Map<String,String>, operation: String, today: LocalDate): PaymentCommand {
        val paid = LocalDate.parse(fields.getValue("paid"))
        require(paid <= today)
        val amount = Money.parse(fields.getValue("amount"),CurrencyCode.of(fields.getValue("currency")))
        require(amount.currency == s.currencyCode)
        require(amount.amountMinor == s.priceMinor || fields["priceChoice"] in setOf("keep","update"))
        val next = if (s.renewalMode == RenewalMode.MANUAL || s.lifecycleState == LifecycleState.PAYMENT_ISSUE)
            LocalDate.parse(fields.getValue("next")).also { require(it > paid) } else null
        return PaymentCommand(OperationId.parse(operation),SubscriptionId(s.id),paid,amount,PaymentSource.CONFIRMED_RENEWAL,
            fields["priceChoice"] == "update",s.toDomain().schedule,s.renewalMode,next,s.lifecycleState,fields["reanchor"] == "true")
    }
}
