package com.glebskorobogatov.subscriptiontracker.data.integration

import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.Settings
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderRules
import com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderEventType as DomainEvent
import java.time.*
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class ReminderInputsTest {
    private val now = Instant.parse("2027-01-01T00:00:00Z")
    private val utc = ZoneId.of("UTC")
    private val id = UUID(0,1)
    private fun row(id: UUID = this.id) = SubscriptionEntity(id = id, name = "Одинаковое имя", accountLabel = "Аккаунт $id", priceMinor = 100, intervalUnit = FrequencyUnit.MONTH, intervalCount = 1, state = SubscriptionState.ACTIVE, anchorYear = 2027, anchorMonth = 1, anchorDay = 31, currentPeriodStartOverride = null, currentPeriodEnd = null, cancelledAccessEndExclusive = null, frozenCancelledPeriodStart = null, logoAssetId = null, catalogLogoId = null, createdAt = now, updatedAt = now, archivedAt = null)

    @Test fun globalDefaultsMapToPureRenewalPlansAndSameNamesRemainIndependent() {
        val settings = Settings(); val rows = listOf(row(UUID(0,2)),row())
        val inputs = reminderInputs(rows,settings)
        assertEquals(LocalTime.of(9,0),settings.reminderLocalTime)
        assertEquals(setOf(3,1),inputs.first().renewalOffsets)
        assertEquals(listOf(UUID(0,1),UUID(0,2)),inputs.map { it.subscription.id.value })
        val plans = ReminderRules().plan(inputs,settings.reminderLocalTime,now,utc)
        assertEquals(4,plans.size); assertEquals(4,plans.map { it.key }.distinct().size)
        assertTrue(plans.all { it.eventType == DomainEvent.RENEWAL && it.plannedLocalDateTime.toLocalTime() == LocalTime.of(9,0) })
        assertEquals(inputs,reminderInputs(rows.reversed(),settings))
    }


    @Test fun emptyGlobalSetDisablesEverySubscription() {
        val settings = Settings(renewalOffsets = emptySet())
        val inputs = reminderInputs(listOf(row(),row(UUID(0,2))),settings)
        assertTrue(ReminderRules().plan(inputs,settings.reminderLocalTime,now,utc).isEmpty())
    }

    @Test fun duplicateRowsFailBeforePlanningAndNoOverrideInputExists() {
        assertThrows(IllegalArgumentException::class.java) { reminderInputs(listOf(row(),row()),Settings()) }
        assertFalse(reminderSubscriptionClass().declaredFields.any { it.name.contains("cancellation",true) })
    }
    private fun reminderSubscriptionClass() = com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderSubscription::class.java

}
