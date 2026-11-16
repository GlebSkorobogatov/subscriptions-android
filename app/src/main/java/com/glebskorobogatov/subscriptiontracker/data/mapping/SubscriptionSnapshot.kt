package com.glebskorobogatov.subscriptiontracker.data.mapping

import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import java.time.*
import java.util.UUID

/** Full expected row, saved as scalars for optimistic non-payment commands and restored drafts. */
object SubscriptionSnapshot {
    fun snapshot(s: SubscriptionEntity): ArrayList<String> = arrayListOf<String?> (
        s.id.toString(),s.name,s.accountLabel,s.priceMinor.toString(),s.currencyCode.value,s.intervalUnit.name,
        s.intervalCount.toString(),s.renewalMode.name,s.lifecycleState.name,s.anchorYear.toString(),s.anchorMonth.toString(),
        s.anchorDay.toString(),s.nextCycleIndex.toString(),s.oneOffDueDate?.toString(),s.overrideCycleIndex?.toString(),
        s.currentPeriodStartOverride?.toString(),s.paidAccessEndDate?.toString(),s.cancellationLeadDays?.toString(),
        s.logoAssetId?.toString(),s.catalogLogoId,s.createdAt.toString(),s.updatedAt.toString()
    ).mapTo(arrayListOf()) { if (it == null) "0" else "1$it" }
    fun row(values: List<String>): SubscriptionEntity {
        require(values.size == 22 && values.all { it == "0" || it.startsWith("1") }) { "Invalid subscription snapshot" }
        val a = values.map { if (it == "0") null else it.substring(1) }
        fun v(i: Int) = requireNotNull(a[i])
        return SubscriptionEntity(UUID.fromString(v(0)),v(1),a[2],v(3).toLong(),CurrencyCode.of(v(4)),
            FrequencyUnit.valueOf(v(5)),v(6).toInt(),RenewalMode.valueOf(v(7)),LifecycleState.valueOf(v(8)),
            v(9).toInt(),v(10).toInt(),v(11).toInt(),v(12).toLong(),a[13]?.let(LocalDate::parse),a[14]?.toLong(),
            a[15]?.let(LocalDate::parse),a[16]?.let(LocalDate::parse),a[17]?.toInt(),a[18]?.let(UUID::fromString),a[19],
            Instant.parse(v(20)),Instant.parse(v(21)))
    }
}
