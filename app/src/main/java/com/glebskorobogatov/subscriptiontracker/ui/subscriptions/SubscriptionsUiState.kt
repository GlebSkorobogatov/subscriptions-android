package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases
import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

sealed interface SubscriptionsUiState {
    data object Loading : SubscriptionsUiState
    data class Empty(val names: Map<UUID, String> = emptyMap()) : SubscriptionsUiState
    data class Content(val forecast: Long?, val cancelled: List<SubscriptionCardModel>,
        val active: List<SubscriptionCardModel>, val names: Map<UUID, String>, val archivedNames: Map<UUID,String> = emptyMap()) : SubscriptionsUiState
    data class StorageBlocked(val failure: com.glebskorobogatov.subscriptiontracker.data.room.StorageFailure) : SubscriptionsUiState
    data object Error : SubscriptionsUiState
}

data class SubscriptionCardModel(val id: UUID, val name: String, val accountLabel: String?,
    val price: Money, val frequency: Frequency, val status: SubscriptionState,
    val eventDate: LocalDate?, val remainingDays: Long?, val remainingFraction: Double?,
    val logoAssetId: UUID? = null, val catalogLogoId: String? = null, val calendarError: Boolean = false)

internal fun card(row: SubscriptionEntity, today: LocalDate): SubscriptionCardModel {
    val detail = com.glebskorobogatov.subscriptiontracker.data.integration.detailsSnapshot(row,today)
    val subscription = row.toDomain()
    val period = detail.period
    return SubscriptionCardModel(row.id, row.name, row.accountLabel, Money(subscription.price.amountMinor, CurrencyCode.of("RUB")),
        subscription.schedule.frequency, subscription.state, period.due, period.remainingDays, period.remainingFraction, row.logoAssetId, row.catalogLogoId, detail.calendarError)
}

internal fun sortedCards(rows: List<SubscriptionEntity>, today: LocalDate) = rows.map { card(it, today) }
    .sortedWith(compareBy<SubscriptionCardModel> { it.status.ordinal }.thenBy(nullsLast()) { it.eventDate }.thenBy { it.id.toString() })

internal fun subscriptionStates(use: SubscriptionUseCases): Flow<SubscriptionsUiState> =
    subscriptionStates(use.forecasts)

internal fun subscriptionStates(snapshots: Flow<com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionForecastSnapshot>): Flow<SubscriptionsUiState> =
    snapshots.map { snapshot ->
        val rows = snapshot.rows
        val today = snapshot.today
        val cards = sortedCards(rows.filter { it.state != SubscriptionState.ARCHIVED }, today)
        val names = rows.associate { it.id to it.name }
        if (cards.isEmpty()) SubscriptionsUiState.Empty(names)
        else SubscriptionsUiState.Content(
            snapshot.forecast?.next30Days?.totalMinor,
            cards.filter { it.status == SubscriptionState.CANCELLED }, cards.filter { it.status == SubscriptionState.ACTIVE }, names,
            rows.filter { it.state == SubscriptionState.ARCHIVED }.associate { it.id to it.name })
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
