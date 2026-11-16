package com.glebskorobogatov.subscriptiontracker.data.mapping

import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import java.time.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SubscriptionMappingTest {
    private val day=LocalDate.of(2027,2,28)
    private val instant=Instant.parse("2027-02-20T12:34:56.123456789Z")
    private val domain=SubscriptionDomain()
    private fun record()=domain.create(SubscriptionId(UUID(0,1)),"Одинаковое имя",RubPrice(12345),
        ScheduleEdit(day,Frequency(FrequencyUnit.MONTH,1),31),day.minusDays(5),"Аккаунт",
        SubscriptionAppearance(logoAssetId=UUID(0,2).toString()),PeriodStart(day.minusDays(10),day))
    @Test fun everyTargetStateAndScopedPeriodMapsLosslesslyWithStorageTimestamps() {
        val active=record(); val cancelled=domain.cancel(active,day.minusDays(5),day.plusDays(3))
        for (record in listOf(active,cancelled,domain.archive(cancelled,day,true))) {
            val row=record.toEntity(instant,instant.plusNanos(1))
            assertEquals(record,row.toDomain()); assertEquals(instant,row.createdAt); assertEquals(instant.plusNanos(1),row.updatedAt)
            assertEquals(row,row.withDomain(row.toDomain(),row.updatedAt))
        }
    }
    @Test fun exactKopecksAndOriginalAnchorAreNotCalculatedStorageState() {
        val row=record().toEntity(instant)
        assertEquals(12345L,row.priceMinor); assertEquals(31,row.anchorDay)
        assertEquals(day,domain.nextDue(row.toDomain().schedule,day))
        assertEquals(LocalDate.of(2027,3,31),domain.nextDue(row.toDomain().schedule,day.plusDays(1)))
        assertEquals(row,record().toEntity(instant))
    }
    @Test fun invalidRoomValuesAreRejectedRatherThanConvertedOrPartiallyMapped() {
        val row=record().toEntity(instant)
        assertThrows(IllegalArgumentException::class.java) { row.copy(priceMinor=0) }
        assertThrows(IllegalArgumentException::class.java) { row.copy(priceMinor=-1) }
        assertThrows(IllegalArgumentException::class.java) { row.copy(intervalCount=0) }
        assertThrows(IllegalArgumentException::class.java) { row.copy(currentPeriodEnd=null) }
        assertThrows(IllegalArgumentException::class.java) { row.copy(catalogLogoId="generic-other") }
    }
    @Test fun runtimeEntityHasNoRetiredProductFieldsAndOnlyThreeStates() {
        val fields=SubscriptionEntity::class.java.declaredFields.map { it.name }.toSet()
        for (name in listOf("currencyCode","renewalMode","nextCycleIndex","oneOffDueDate","overrideCycleIndex","paidAccessEndDate","cancellationLeadDays")) assertFalse(name, name in fields)
        assertEquals(listOf("ACTIVE","CANCELLED","ARCHIVED"),SubscriptionState.entries.map { it.name })
    }
}
