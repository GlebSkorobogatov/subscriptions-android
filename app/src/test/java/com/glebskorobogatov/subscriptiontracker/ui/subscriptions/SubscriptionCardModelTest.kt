package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class SubscriptionCardModelTest {
    private val day = LocalDate.of(2027, 1, 31)
    private fun row(id: Long = 1) = SubscriptionEntity(UUID(0, id), "Сервис", "Личный", 12345, CurrencyCode.of("RUB"),
        FrequencyUnit.MONTH, 1, RenewalMode.AUTO, LifecycleState.ACTIVE, 2027, 1, 31, 0,
        null, null, null, null, null, null, null, Instant.EPOCH, Instant.EPOCH)

    @Test fun unknownCancellationDateHasNoCountdownOrProgress() {
        val card = card(row().copy(lifecycleState = LifecycleState.CANCELLATION_SCHEDULED), day)
        assertNull(card.eventDate); assertNull(card.remainingDays); assertNull(card.remainingFraction)
        assertEquals(VisibleStatus.CANCELLATION_SCHEDULED, card.status)
    }
    @Test fun knownAccessEndUsesStoredDateAndDomainProgress() {
        val card = card(row().copy(lifecycleState = LifecycleState.CANCELLATION_SCHEDULED,
            paidAccessEndDate = day.plusDays(4), currentPeriodStartOverride = day.minusDays(4)), day)
        assertEquals(day.plusDays(4), card.eventDate); assertEquals(4L, card.remainingDays)
        assertEquals(0.5f, card.remainingFraction!!, 0.0001f)
    }
    @Test fun automaticSortUsesDatesThenUuidAndUnknownLast() {
        val unknown = row(1).copy(lifecycleState = LifecycleState.CANCELLATION_SCHEDULED)
        val later = row(2).copy(anchorMonth = 2, anchorDay = 1)
        assertEquals(listOf(UUID(0,3), UUID(0,4), UUID(0,2), UUID(0,1)),
            sortedCards(listOf(unknown, later, row(4), row(3)), day).map { it.id })
    }
    @Test fun overdueManualAndPaymentIssueDoNotInventFutureDates() {
        val manual = card(row().copy(renewalMode = RenewalMode.MANUAL), day.plusDays(1))
        assertEquals(VisibleStatus.AWAITING_RENEWAL, manual.status)
        assertNull(manual.eventDate); assertNull(manual.remainingFraction)
        val issue = card(row().copy(lifecycleState = LifecycleState.PAYMENT_ISSUE), day)
        assertEquals(VisibleStatus.PAYMENT_ISSUE, issue.status); assertNull(issue.eventDate)
    }
    @Test fun namesLabelsAndCurrencyPrecisionArePreserved() {
        val cards = sortedCards(listOf(row(), row(2).copy(accountLabel = "Семейный", currencyCode = CurrencyCode.of("JPY"))), day)
        assertEquals(listOf("Личный", "Семейный"), cards.map { it.accountLabel })
        assertEquals(listOf("123,45", "12345"), cards.map { it.price.format() })
        assertEquals(2, cards.map { it.id }.distinct().size)
    }
}
