package com.glebskorobogatov.subscriptiontracker.ui.details

import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.mapping.SubscriptionSnapshot
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import java.time.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

internal fun detailsRow() = SubscriptionEntity(id = UUID(0,1), name = "Сервис", accountLabel = "Личный", priceMinor = 49900, intervalUnit = FrequencyUnit.MONTH, intervalCount = 1, state = SubscriptionState.ACTIVE, anchorYear = 2027, anchorMonth = 1, anchorDay = 31, currentPeriodStartOverride = null, currentPeriodEnd = null, cancelledAccessEndExclusive = null, frozenCancelledPeriodStart = null, logoAssetId = null, catalogLogoId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, archivedAt = null)
class SubscriptionSnapshotTest {
    private val day = LocalDate.of(2027,1,31)
    @Test fun snapshotRoundTripPreservesEveryColumnIncludingNullableValues() {
        val a = detailsRow().copy(name = "0|1",accountLabel = "",state = SubscriptionState.CANCELLED,
            currentPeriodStartOverride = day.minusDays(15),currentPeriodEnd = day,
            cancelledAccessEndExclusive = day.plusDays(5),frozenCancelledPeriodStart = day.minusDays(15),
            logoAssetId = UUID(0,3),updatedAt = Instant.parse("2027-01-31T10:00:00.123Z"))
        assertEquals(a,SubscriptionSnapshot.row(SubscriptionSnapshot.snapshot(a)))
        assertEquals(detailsRow().copy(accountLabel = null),SubscriptionSnapshot.row(SubscriptionSnapshot.snapshot(detailsRow().copy(accountLabel = null))))
    }
    @Test fun malformedSnapshotsAreRejectedInsteadOfDroppingColumnsOrGuessingNulls() {
        val valid = SubscriptionSnapshot.snapshot(detailsRow())
        for (invalid in listOf(valid.dropLast(1),valid + "0",valid.toMutableList().also { it[2] = "x" },
            valid.toMutableList().also { it[0] = "0" })) {
            assertThrows(IllegalArgumentException::class.java) { SubscriptionSnapshot.row(invalid) }
        }
    }

}
