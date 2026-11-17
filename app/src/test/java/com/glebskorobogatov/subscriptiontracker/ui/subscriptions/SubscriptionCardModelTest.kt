package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class SubscriptionCardModelTest {
    private val day = LocalDate.of(2027, 1, 31)
    private fun row(id: Long = 1) = SubscriptionEntity(id = UUID(0, id), name = "Сервис", accountLabel = "Личный", priceMinor = 12345, intervalUnit = FrequencyUnit.MONTH, intervalCount = 1, state = SubscriptionState.ACTIVE, anchorYear = 2027, anchorMonth = 1, anchorDay = 31, currentPeriodStartOverride = null, currentPeriodEnd = null, cancelledAccessEndExclusive = null, frozenCancelledPeriodStart = null, logoAssetId = null, catalogLogoId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, archivedAt = null)

    @Test fun unknownCancellationDateHasNoCountdownOrProgress() {
        val card = card(row().copy(state = SubscriptionState.CANCELLED), day)
        assertNull(card.eventDate); assertNull(card.remainingDays); assertNull(card.remainingFraction)
        assertEquals(SubscriptionState.CANCELLED, card.status)
    }
    @Test fun knownAccessEndUsesStoredDateAndDomainProgress() {
        val card = card(row().copy(state = SubscriptionState.CANCELLED,
            cancelledAccessEndExclusive = day.plusDays(4), frozenCancelledPeriodStart = day.minusDays(4)), day)
        assertEquals(day.plusDays(4), card.eventDate); assertEquals(4L, card.remainingDays)
        assertEquals(0.5f, card.remainingFraction!!, 0.0001f)
    }
    @Test fun automaticSortUsesDatesThenUuidAndUnknownLast() {
        val unknown = row(1).copy(state = SubscriptionState.CANCELLED)
        val later = row(2).copy(anchorMonth = 2, anchorDay = 1)
        assertEquals(listOf(UUID(0,3), UUID(0,4), UUID(0,2), UUID(0,1)),
            sortedCards(listOf(unknown, later, row(4), row(3)), day).map { it.id })
    }
    @Test fun activeDateAdvancesWithoutMutationOrProblemStatus() {
        val row=row(); val card=card(row,day.plusDays(1))
        assertEquals(SubscriptionState.ACTIVE,card.status); assertEquals(LocalDate.of(2027,2,28),card.eventDate)
        assertEquals(day.dayOfMonth,row.anchorDay)
    }
    @Test fun namesLabelsAndCurrencyPrecisionArePreserved() {
        val cards = sortedCards(listOf(row(), row(2).copy(accountLabel = "Семейный")), day)
        assertEquals(listOf("Личный", "Семейный"), cards.map { it.accountLabel })
        assertEquals(listOf("123,45", "123,45"), cards.map { it.price.format() })
        assertEquals(2, cards.map { it.id }.distinct().size)
    }
}
