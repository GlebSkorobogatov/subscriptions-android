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
    private fun override(type: ReminderEventType,mode: ReminderMode,days: Set<Int>) = ReminderOverrideEntity(id,type,mode,days)

    @Test fun actualC02DefaultsMapToPurePlansAndSameNamesRemainIndependent() {
        val settings = Settings(); val rows = listOf(row(UUID(0,2)),row())
        val inputs = reminderInputs(rows,emptyList(),settings)
        assertEquals(LocalTime.of(9,0),settings.reminderLocalTime)
        assertEquals(setOf(3,1),inputs.first().paymentOffsets); assertEquals(setOf(7,3),inputs.first().cancellationOffsets)
        assertEquals(listOf(UUID(0,1),UUID(0,2)),inputs.map { it.subscription.id.value })
        val plans = ReminderRules().plan(inputs,settings.reminderLocalTime,now,utc)
        assertEquals(8,plans.size); assertEquals(8,plans.map { it.key }.distinct().size)
        assertTrue(plans.all { it.plannedLocalDateTime.toLocalTime() == LocalTime.of(9,0) })
        assertEquals(inputs,reminderInputs(rows.reversed(),emptyList(),settings))
    }
    @Test fun inheritanceUsesExistingEffectiveOffsetsIndependentlyAndNeverMutatesCustom() {
        val original = Settings()
        val changed = original.copy(paymentOffsetsDefault = setOf(7,0),cancellationOffsetsDefault = setOf(1))
        val customPayment = override(ReminderEventType.PAYMENT,ReminderMode.CUSTOM,setOf(0))
        val inheritCancel = override(ReminderEventType.CANCELLATION,ReminderMode.INHERIT_GLOBAL,setOf(7))
        val a = reminderInputs(listOf(row()),listOf(customPayment,inheritCancel),original).single()
        val b = reminderInputs(listOf(row()),listOf(customPayment,inheritCancel),changed).single()
        assertEquals(a.paymentOffsets,b.paymentOffsets); assertEquals(setOf(0),b.paymentOffsets)
        assertEquals(setOf(7,3),a.cancellationOffsets); assertEquals(setOf(1),b.cancellationOffsets)
        assertEquals(setOf(0),customPayment.offsets)
        val customCancel = override(ReminderEventType.CANCELLATION,ReminderMode.CUSTOM,setOf(7,3,1))
        val inheritPayment = override(ReminderEventType.PAYMENT,ReminderMode.INHERIT_GLOBAL,emptySet())
        val c = reminderInputs(listOf(row()),listOf(inheritPayment,customCancel),changed).single()
        assertEquals(setOf(7,0),c.paymentOffsets); assertEquals(setOf(7,3,1),c.cancellationOffsets)
        assertEquals(setOf(7,3,1),customCancel.offsets); assertEquals(Settings(),original)
    }
    @Test fun emptyCustomDisablesOnlyItsKindAndGlobalEmptyDoesNotOverrideCustom() {
        val settings = Settings()
        val rules = ReminderRules()
        val payOff = reminderInputs(listOf(row()),listOf(override(ReminderEventType.PAYMENT,ReminderMode.CUSTOM,emptySet())),settings)
        assertEquals(setOf(DomainEvent.CANCELLATION),rules.plan(payOff,settings.reminderLocalTime,now,utc).map { it.eventType }.toSet())
        val cancelOff = reminderInputs(listOf(row()),listOf(override(ReminderEventType.CANCELLATION,ReminderMode.CUSTOM,emptySet())),settings)
        assertEquals(setOf(DomainEvent.PAYMENT),rules.plan(cancelOff,settings.reminderLocalTime,now,utc).map { it.eventType }.toSet())
        val both = reminderInputs(listOf(row()),listOf(override(ReminderEventType.PAYMENT,ReminderMode.CUSTOM,setOf(1)),
            override(ReminderEventType.CANCELLATION,ReminderMode.CUSTOM,setOf(0))),settings.copy(paymentOffsetsDefault = emptySet(),cancellationOffsetsDefault = emptySet()))
        assertEquals(setOf(0,1),rules.plan(both,settings.reminderLocalTime,now,utc).map { it.offsetDays }.toSet())
    }
    @Test fun duplicateRowsDuplicateOverridesAndForeignOverridesFailBeforePlanning() {
        val value = override(ReminderEventType.PAYMENT,ReminderMode.CUSTOM,setOf(1))
        assertThrows(IllegalArgumentException::class.java) { reminderInputs(listOf(row(),row()),emptyList(),Settings()) }
        assertThrows(IllegalArgumentException::class.java) { reminderInputs(listOf(row()),listOf(value,value),Settings()) }
        assertThrows(IllegalArgumentException::class.java) { reminderInputs(listOf(row()),listOf(value.copy(subscriptionId = UUID(0,2))),Settings()) }
    }
}
