package com.glebskorobogatov.subscriptiontracker.data.mapping

import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.domain.calendar.RenewalAnchor
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

fun SubscriptionEntity.toDomain() = SubscriptionRecord(SubscriptionId(id), name, RubPrice(priceMinor),
    RecurringSchedule(RenewalAnchor(YearMonth.of(anchorYear, anchorMonth), anchorDay), Frequency(intervalUnit, intervalCount)),
    accountLabel, SubscriptionAppearance(catalogLogoId, logoAssetId?.toString()), state,
    currentPeriodStartOverride?.let { PeriodStart(it, requireNotNull(currentPeriodEnd)) },
    cancelledAccessEndExclusive, frozenCancelledPeriodStart, archivedAt)

fun SubscriptionRecord.toEntity(createdAt: Instant, updatedAt: Instant = createdAt) = SubscriptionEntity(
    id.value, name, accountLabel, price.amountMinor, schedule.frequency.unit, schedule.frequency.count, state,
    schedule.anchor.yearMonth.year, schedule.anchor.yearMonth.monthValue, schedule.anchor.day,
    periodStart?.start, periodStart?.due, cancelledAccessEndExclusive, frozenCancelledPeriodStart,
    appearance.logoAssetId?.let(UUID::fromString), appearance.catalogLogoId, createdAt, updatedAt, archivedAt)

fun SubscriptionEntity.withDomain(s: SubscriptionRecord, changedAt: Instant): SubscriptionEntity {
    require(id == s.id.value)
    return s.toEntity(createdAt, changedAt)
}
