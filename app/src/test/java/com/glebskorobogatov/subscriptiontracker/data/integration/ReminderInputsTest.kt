package com.glebskorobogatov.subscriptiontracker.data.integration

import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.Settings
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
    private fun row(id: UUID = this.id) = SubscriptionEntity(id,"Одинаковое имя","Аккаунт $id",100,CurrencyCode.of("RUB"),
        FrequencyUnit.MONTH,1,RenewalMode.AUTO,LifecycleState.ACTIVE,2027,1,31,0,null,null,null,null,5,null,null,now,now)

    @Test fun globalDefaultsMapToPureRenewalPlansAndSameNamesRemainIndependent() {
        val settings = Settings(); val rows = listOf(row(UUID(0,2)),row())
        val inputs = reminderInputs(rows,settings)
        assertEquals(LocalTime.of(9,0),settings.reminderLocalTime)
        assertEquals(setOf(3,1),inputs.first().paymentOffsets)
        assertEquals(listOf(UUID(0,1),UUID(0,2)),inputs.map { it.subscription.id.value })
        val plans = ReminderRules().plan(inputs,settings.reminderLocalTime,now,utc)
        assertEquals(4,plans.size); assertEquals(4,plans.map { it.key }.distinct().size)
        assertTrue(plans.all { it.eventType == DomainEvent.PAYMENT && it.plannedLocalDateTime.toLocalTime() == LocalTime.of(9,0) })
        assertEquals(inputs,reminderInputs(rows.reversed(),settings))
    }

    @Test fun legacyCancellationLeadCannotAffectGlobalRenewalPlan() {
        val before = row(); val settings = Settings(paymentOffsetsDefault = setOf(7,0))
        val baseline = reminderInputs(listOf(before),settings)
        for (lead in listOf(null,0,1,999)) {
            assertEquals(baseline,reminderInputs(listOf(before.copy(cancellationLeadDays = lead)),settings))
        }
        assertEquals(5,before.cancellationLeadDays)
    }

    @Test fun emptyGlobalSetDisablesEverySubscription() {
        val settings = Settings(paymentOffsetsDefault = emptySet())
        val inputs = reminderInputs(listOf(row(),row(UUID(0,2))),settings)
        assertTrue(ReminderRules().plan(inputs,settings.reminderLocalTime,now,utc).isEmpty())
    }

    @Test fun duplicateRowsFailBeforePlanningAndNoOverrideInputExists() {
        assertThrows(IllegalArgumentException::class.java) { reminderInputs(listOf(row(),row()),Settings()) }
        assertFalse(reminderSubscriptionClass().declaredFields.any { it.name.contains("cancellation",true) })
    }
    private fun reminderSubscriptionClass() = com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderSubscription::class.java

}
