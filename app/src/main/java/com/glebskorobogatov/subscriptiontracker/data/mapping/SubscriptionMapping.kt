package com.glebskorobogatov.subscriptiontracker.data.mapping

import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.Instant
import java.time.YearMonth

fun SubscriptionEntity.toDomain() = Subscription(SubscriptionId(id), renewalMode,
    RenewalSchedule(RenewalAnchor(YearMonth.of(anchorYear, anchorMonth), anchorDay), Frequency(intervalUnit, intervalCount),
        nextCycleIndex, oneOffDueDate?.let { CycleOverride(requireNotNull(overrideCycleIndex), it) }),
    Money(priceMinor, currencyCode), lifecycleState, paidAccessEndDate, currentPeriodStartOverride)

/** Preserve storage-only metadata while persisting every lifecycle/schedule field. */
fun SubscriptionEntity.withDomain(s: Subscription, changedAt: Instant): SubscriptionEntity {
    require(id == s.id.value)
    return copy(renewalMode = s.mode, lifecycleState = s.state,
        priceMinor = s.price.amountMinor, currencyCode = s.price.currency,
        intervalUnit = s.schedule.frequency.unit, intervalCount = s.schedule.frequency.count,
        anchorYear = s.schedule.anchor.yearMonth.year, anchorMonth = s.schedule.anchor.yearMonth.monthValue,
        anchorDay = s.schedule.anchor.day, nextCycleIndex = s.schedule.nextCycleIndex,
        oneOffDueDate = s.schedule.oneOff?.date, overrideCycleIndex = s.schedule.oneOff?.cycleIndex,
        currentPeriodStartOverride = s.periodStartOverride, paidAccessEndDate = s.paidAccessEnd, updatedAt = changedAt)
}
