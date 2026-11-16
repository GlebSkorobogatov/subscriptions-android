package com.glebskorobogatov.subscriptiontracker.ui.details

import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.mapping.SubscriptionSnapshot
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import java.time.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

internal fun detailsRow() = SubscriptionEntity(UUID(0,1),"Сервис","Личный",49900,CurrencyCode.of("RUB"),FrequencyUnit.MONTH,1,
    RenewalMode.AUTO,LifecycleState.ACTIVE,2027,1,31,0,null,null,null,null,null,null,null,Instant.EPOCH,Instant.EPOCH)
class SubscriptionSnapshotTest {
    private val day = LocalDate.of(2027,1,31)
    @Test fun snapshotRoundTripPreservesEveryColumnIncludingNullableValues() {
        val a = detailsRow().copy(name = "0|1",accountLabel = "",oneOffDueDate = day.minusDays(1),overrideCycleIndex = 0,
            currentPeriodStartOverride = day.minusDays(15),paidAccessEndDate = day.plusDays(5),cancellationLeadDays = 3,
            logoAssetId = UUID(0,3),catalogLogoId = "0",updatedAt = Instant.parse("2027-01-31T10:00:00.123Z"))
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
