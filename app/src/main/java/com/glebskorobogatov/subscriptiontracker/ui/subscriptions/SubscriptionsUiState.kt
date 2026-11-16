package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases
import com.glebskorobogatov.subscriptiontracker.data.payment.toDomain
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

sealed interface SubscriptionsUiState {
    data object Loading : SubscriptionsUiState
    data class Empty(val names: Map<UUID, String> = emptyMap()) : SubscriptionsUiState
    data class Content(val forecast: List<Money>, val needsDecision: List<SubscriptionCardModel>,
        val active: List<SubscriptionCardModel>, val names: Map<UUID, String>, val archivedNames: Map<UUID,String> = emptyMap()) : SubscriptionsUiState
    data object Error : SubscriptionsUiState
}

data class SubscriptionCardModel(val id: UUID, val name: String, val accountLabel: String?,
    val price: Money, val frequency: Frequency, val mode: RenewalMode, val status: VisibleStatus,
    val eventDate: LocalDate?, val remainingDays: Long?, val remainingFraction: Float?,
    val logoAssetId: UUID? = null, val catalogLogoId: String? = null)

internal fun card(row: SubscriptionEntity, today: LocalDate): SubscriptionCardModel {
    val subscription = row.toDomain()
    val view = SubscriptionLifecycleService().view(subscription, today)
    // A stored, explicitly known access end is not a calculated renewal date.
    val date = if (view.status == VisibleStatus.CANCELLATION_SCHEDULED) subscription.paidAccessEnd else view.expectedDate
    return SubscriptionCardModel(row.id, row.name, row.accountLabel, subscription.price,
        subscription.schedule.frequency, subscription.mode, view.status, date,
        date?.let { ChronoUnit.DAYS.between(today, it) }, view.remainingFraction?.toFloat(), row.logoAssetId, row.catalogLogoId)
}

internal fun sortedCards(rows: List<SubscriptionEntity>, today: LocalDate) = rows.map { card(it, today) }
    .sortedWith(compareBy<SubscriptionCardModel> { it.eventDate == null }.thenBy { it.eventDate }.thenBy { it.id.toString() })

internal fun subscriptionStates(use: SubscriptionUseCases): Flow<SubscriptionsUiState> {
    val groups = combine(use.active, use.needsDecision, use.archive) { active, decision, archive ->
        Triple(active, decision, archive)
    }
    return combine(use.subscriptions, groups, use.forecast30Days, use.date.today) { rows, group, forecast, today ->
        val classified = group.first + group.second + group.third
        // Independent Room collectors can emit at different times. Never publish a missing/duplicate
        // card while classification still refers to a previous subscription snapshot.
        if (classified.size != rows.size || classified.associateBy { it.id.value } != rows.associate { it.id to it.toDomain() }) {
            null
        } else {
            val names = rows.associate { it.id to it.name }
            val byId = rows.associateBy { it.id }
            val active = sortedCards(group.first.map { byId.getValue(it.id.value) }, today)
            val decisions = sortedCards(group.second.map { byId.getValue(it.id.value) }, today)
            if (active.isEmpty() && decisions.isEmpty()) SubscriptionsUiState.Empty(names)
            else SubscriptionsUiState.Content(forecast.totalsByCurrency.entries.sortedBy { it.key.value }.map { it.value },
                decisions, active, names, group.third.associate { it.id.value to names.getValue(it.id.value) })
        }
    }.filterNotNull().distinctUntilChanged().flowOn(Dispatchers.Default)
}
