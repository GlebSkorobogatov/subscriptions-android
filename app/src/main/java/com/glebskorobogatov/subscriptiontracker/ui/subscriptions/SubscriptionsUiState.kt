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
    data class Content(val cancelled: List<SubscriptionCardModel>,
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

/** Library reads rows/today directly. Opening the home never subscribes to a financial projection. */
internal fun subscriptionStates(use: SubscriptionUseCases): Flow<SubscriptionsUiState> =
    subscriptionStates(use.subscriptions,use.date.today)

internal fun subscriptionStates(rows: Flow<List<SubscriptionEntity>>, today: Flow<LocalDate>): Flow<SubscriptionsUiState> =
    combine(rows,today) { snapshot,date -> librarySnapshot(snapshot,date) }.distinctUntilChanged().flowOn(Dispatchers.Default)

internal fun librarySnapshot(rows: List<SubscriptionEntity>, today: LocalDate): SubscriptionsUiState {
    val cards = sortedCards(rows.filter { it.state != SubscriptionState.ARCHIVED },today)
    val names = rows.associate { it.id to it.name }
    return if(cards.isEmpty()) SubscriptionsUiState.Empty(names)
    else SubscriptionsUiState.Content(cards.filter { it.status == SubscriptionState.CANCELLED },
        cards.filter { it.status == SubscriptionState.ACTIVE },names,
        rows.filter { it.state == SubscriptionState.ARCHIVED }.associate { it.id to it.name })
}
