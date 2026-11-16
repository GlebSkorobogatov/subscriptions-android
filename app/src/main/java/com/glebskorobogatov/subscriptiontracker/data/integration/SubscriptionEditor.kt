package com.glebskorobogatov.subscriptiontracker.data.integration

import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.*
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.*

sealed interface ScheduleEdit {
    data object Keep : ScheduleEdit
    data class NextOnly(val date: LocalDate) : ScheduleEdit
    data class FutureSeries(val firstDate: LocalDate, val frequency: Frequency, val anchor: RenewalAnchor? = null) : ScheduleEdit
}

sealed interface ImageEdit {
    data object Keep : ImageEdit
    data class Set(val assetId: UUID) : ImageEdit
    data class Catalog(val id: String) : ImageEdit
    data object Remove : ImageEdit
}

data class SubscriptionDraft(
    val id: UUID, val name: String, val accountLabel: String?, val price: Money,
    val mode: RenewalMode, val schedule: RenewalSchedule,
    val periodStart: LocalDate? = null, val paidAccessEnd: LocalDate? = null,
    val logoAssetId: UUID? = null, val catalogLogoId: String? = null,
)

data class SubscriptionEdit(
    val name: String, val accountLabel: String?, val price: Money, val mode: RenewalMode,
    val schedule: ScheduleEdit = ScheduleEdit.Keep,
    val extras: SubscriptionExtras? = null,
    val image: ImageEdit = ImageEdit.Keep,

)

data class SubscriptionExtras(val periodStart: LocalDate?, val paidAccessEnd: LocalDate?)

/** Only the missing creation/edit persistence. Lifecycle and money writes stay in C-03–05. */
internal class SubscriptionEditor(private val db: TrackerDatabase,
    private val calendar: DefaultRenewalCalendar = DefaultRenewalCalendar()) {
    suspend fun create(draft: SubscriptionDraft, recordedAt: Instant, image: AssetEntity? = null): SubscriptionEntity = withContext(Dispatchers.IO) {
        db.runInTransaction(Callable {
            if (db.subscriptions().find(draft.id) != null) throw LifecycleConflict()
            require(draft.price.currency == CurrencyCode.of("RUB"))
            require(draft.logoAssetId == null || draft.catalogLogoId == null)
            draft.catalogLogoId?.let(com.glebskorobogatov.subscriptiontracker.data.catalog.OfflineIconCatalog::requireKnown)
            image?.let { require(draft.logoAssetId == it.id); db.assets().insert(it) }
            require(draft.periodStart == null || draft.paidAccessEnd == null || draft.periodStart < draft.paidAccessEnd)
            val s = Subscription(SubscriptionId(draft.id), draft.mode, draft.schedule, draft.price,
                periodStartOverride = draft.periodStart, paidAccessEnd = draft.paidAccessEnd)
            val row = SubscriptionEntity(draft.id, draft.name, draft.accountLabel, s.price.amountMinor, s.price.currency,
                s.schedule.frequency.unit, s.schedule.frequency.count, s.mode, s.state,
                s.schedule.anchor.yearMonth.year, s.schedule.anchor.yearMonth.monthValue, s.schedule.anchor.day,
                s.schedule.nextCycleIndex, s.schedule.oneOff?.date, s.schedule.oneOff?.cycleIndex,
                draft.periodStart, draft.paidAccessEnd, null, draft.logoAssetId, draft.catalogLogoId,
                recordedAt, recordedAt)
            db.subscriptions().insert(row)
            row
        })
    }
    suspend fun edit(expected: SubscriptionEntity, edit: SubscriptionEdit, changedAt: Instant, image: AssetEntity? = null): SubscriptionEntity = withContext(Dispatchers.IO) {
        db.runInTransaction(Callable {
            val current = db.subscriptions().find(expected.id)
            if (current != expected) throw LifecycleConflict()
            require(edit.price.currency == expected.currencyCode)
            val s = expected.toDomain()
            if ((edit.schedule != ScheduleEdit.Keep || edit.mode != s.mode) && s.state != LifecycleState.ACTIVE)
                throw InvalidTransition("Изменение графика/режима требует ACTIVE; используйте разрешение проблемы или реактивацию")
            val schedule = when (val change = edit.schedule) {
                ScheduleEdit.Keep -> s.schedule
                is ScheduleEdit.NextOnly -> calendar.moveNextOnly(s.schedule, change.date)
                is ScheduleEdit.FutureSeries -> change.anchor?.let {
                    val base = RenewalSchedule(it, change.frequency)
                    val index = calendar.firstCycleIndexOnOrAfter(base, change.firstDate) ?: error("Invalid date")
                    calendar.moveNextOnly(base.copy(nextCycleIndex = index), change.firstDate)
                }
                    ?: calendar.moveFutureSeries(s.schedule, change.firstDate).copy(frequency = change.frequency)
            }
            calendar.nextScheduledDate(schedule)
            var updated = expected.withDomain(s.copy(price = edit.price.requirePositive(), mode = edit.mode, schedule = schedule), changedAt)
                .copy(name = edit.name, accountLabel = edit.accountLabel)
            updated = when (val change = edit.image) {
                ImageEdit.Keep -> updated
                ImageEdit.Remove -> updated.copy(logoAssetId = null, catalogLogoId = null)
                is ImageEdit.Set -> {
                    val prepared = requireNotNull(image)
                    require(prepared.id == change.assetId)
                    db.assets().insert(prepared)
                    updated.copy(logoAssetId = prepared.id, catalogLogoId = null)
                }
                is ImageEdit.Catalog -> {
                    com.glebskorobogatov.subscriptiontracker.data.catalog.OfflineIconCatalog.requireKnown(change.id)
                    updated.copy(logoAssetId = null, catalogLogoId = change.id)
                }
            }
            edit.extras?.let {
                require(it.periodStart == null || it.paidAccessEnd == null || it.periodStart < it.paidAccessEnd)
                updated = updated.copy(currentPeriodStartOverride = it.periodStart, paidAccessEndDate = it.paidAccessEnd)
            }
            check(db.subscriptions().update(updated) == 1)
            updated
        })
    }
}
