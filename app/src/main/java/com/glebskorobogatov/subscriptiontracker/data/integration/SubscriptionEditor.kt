package com.glebskorobogatov.subscriptiontracker.data.integration

import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.*
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict
import com.glebskorobogatov.subscriptiontracker.data.catalog.OfflineIconCatalog
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import java.time.*
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.*

sealed interface ImageEdit {
    data object Keep : ImageEdit
    data class Set(val assetId: UUID) : ImageEdit
    data class Catalog(val id: String) : ImageEdit
    data object Remove : ImageEdit
}
data class SubscriptionDraft(val id: UUID, val name: String, val accountLabel: String?, val price: RubPrice,
    val schedule: ScheduleEdit, val periodStart: PeriodStart? = null,
    val logoAssetId: UUID? = null, val catalogLogoId: String? = null)
sealed interface PeriodStartEdit {
    data object Keep : PeriodStartEdit
    data class Set(val value: PeriodStart?) : PeriodStartEdit
}
data class SubscriptionEdit(val name: String, val accountLabel: String?, val price: RubPrice,
    val schedule: ScheduleEdit? = null, val periodStart: PeriodStartEdit = PeriodStartEdit.Keep, val image: ImageEdit = ImageEdit.Keep)

internal class SubscriptionEditor(private val db: TrackerDatabase, private val domain: SubscriptionDomain = SubscriptionDomain()) {
    suspend fun create(draft: SubscriptionDraft, recordedAt: Instant, today: LocalDate, image: AssetEntity? = null): SubscriptionEntity = withContext(Dispatchers.IO) {
        db.runInTransaction(Callable {
            if (db.subscriptions().find(draft.id) != null) throw LifecycleConflict()
            require(draft.logoAssetId == null || draft.catalogLogoId == null)
            draft.catalogLogoId?.let(OfflineIconCatalog::requireKnown)
            image?.let { require(draft.logoAssetId == it.id); db.assets().insert(it) }
            val record = domain.create(SubscriptionId(draft.id), draft.name, draft.price, draft.schedule, today,
                draft.accountLabel, SubscriptionAppearance(draft.catalogLogoId, draft.logoAssetId?.toString()), draft.periodStart)
            record.toEntity(recordedAt).also { db.subscriptions().insert(it) }
        })
    }
    suspend fun edit(expected: SubscriptionEntity, edit: SubscriptionEdit, changedAt: Instant, today: LocalDate, image: AssetEntity? = null): SubscriptionEntity = withContext(Dispatchers.IO) {
        db.runInTransaction(Callable {
            if (db.subscriptions().find(expected.id) != expected) throw LifecycleConflict()
            val old = expected.toDomain()
            require(edit.schedule == null || old.state == SubscriptionState.ACTIVE)
            require(old.state == SubscriptionState.ACTIVE || edit.periodStart == PeriodStartEdit.Keep ||
                (edit.periodStart as? PeriodStartEdit.Set)?.value == old.periodStart)
            val appearance = when (val change = edit.image) {
                ImageEdit.Keep -> old.appearance
                ImageEdit.Remove -> SubscriptionAppearance()
                is ImageEdit.Set -> {
                    val prepared = requireNotNull(image)
                    require(prepared.id == change.assetId)
                    db.assets().insert(prepared)
                    SubscriptionAppearance(logoAssetId = prepared.id.toString())
                }
                is ImageEdit.Catalog -> {
                    OfflineIconCatalog.requireKnown(change.id)
                    SubscriptionAppearance(catalogLogoId = change.id)
                }
            }
            val result = domain.edit(old, today, edit.name, edit.price, edit.accountLabel, appearance,
                edit.schedule, if (old.state != SubscriptionState.ACTIVE) old.periodStart else when (val change = edit.periodStart) {
                    PeriodStartEdit.Keep -> if (edit.schedule == null) old.periodStart else null
                    is PeriodStartEdit.Set -> change.value
                })
            expected.withDomain(result, changedAt).also { check(db.subscriptions().update(it) == 1) }
        })
    }
}
