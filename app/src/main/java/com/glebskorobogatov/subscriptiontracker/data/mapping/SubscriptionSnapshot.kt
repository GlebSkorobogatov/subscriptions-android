package com.glebskorobogatov.subscriptiontracker.data.mapping

import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState
import java.time.*
import java.util.UUID

/** Versioned full optimistic row; legacy snapshots are rejected, never executed. */
object SubscriptionSnapshot {
    fun snapshot(s: SubscriptionEntity): ArrayList<String> = arrayListOf<String?>(
        "subscription-v5", s.id.toString(), s.name, s.accountLabel, s.priceMinor.toString(), s.intervalUnit.name,
        s.intervalCount.toString(), s.state.name, s.anchorYear.toString(), s.anchorMonth.toString(), s.anchorDay.toString(),
        s.currentPeriodStartOverride?.toString(), s.currentPeriodEnd?.toString(), s.cancelledAccessEndExclusive?.toString(),
        s.frozenCancelledPeriodStart?.toString(), s.logoAssetId?.toString(), s.catalogLogoId,
        s.createdAt.toString(), s.updatedAt.toString(), s.archivedAt?.toString()
    ).mapTo(arrayListOf()) { if (it == null) "0" else "1$it" }
    fun row(values: List<String>): SubscriptionEntity {
        require(values.size == 20 && values.all { it == "0" || it.startsWith("1") })
        val a = values.map { if (it == "0") null else it.substring(1) }
        fun v(i: Int) = requireNotNull(a[i])
        require(v(0) == "subscription-v5")
        fun date(i: Int) = a[i]?.let(LocalDate::parse)
        return SubscriptionEntity(UUID.fromString(v(1)), v(2), a[3], v(4).toLong(), FrequencyUnit.valueOf(v(5)),
            v(6).toInt(), SubscriptionState.valueOf(v(7)), v(8).toInt(), v(9).toInt(), v(10).toInt(),
            date(11), date(12), date(13), date(14), a[15]?.let(UUID::fromString), a[16],
            Instant.parse(v(17)), Instant.parse(v(18)), date(19))
    }
}
