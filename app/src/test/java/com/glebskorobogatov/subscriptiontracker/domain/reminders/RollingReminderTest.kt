package com.glebskorobogatov.subscriptiontracker.domain.reminders

import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class RollingReminderTest {
    private val rules = ReminderRules()
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026,11,16)
    private val time = LocalTime.of(9,0)
    private val now = day.atTime(time).toInstant(zone)
    private fun input(unit: FrequencyUnit = FrequencyUnit.DAY, count: Int = 1,
        anchor: LocalDate = day, offsets: Set<Int> = setOf(7,3,1,0), id: Long = 1) =
        ReminderSubscription(SubscriptionRecord(SubscriptionId(UUID(0,id)), "Синтетический сервис", RubPrice(123),
            RecurringSchedule.starting(anchor,Frequency(unit,count))),offsets)
    private fun plan(s: ReminderSubscription = input(), at: Instant = now) = rules.plan(listOf(s),time,at,zone)

    @Test fun dailyIncludesEveryCycleForAllOffsetsInSevenReminderDates() {
        val events = plan()
        assertEquals(28,events.size)
        assertEquals(28,events.map { it.key }.distinct().size)
        assertEquals((0L..6L).map { day.plusDays(it) }.toSet(),events.map { it.plannedLocalDateTime.toLocalDate() }.toSet())
        assertEquals(day.plusDays(13),events.maxOf { it.targetDate })
    }
    @Test fun simultaneousCyclesFormOneMessageWithFourDistinctDueDates() {
        val s = input(); val message = rules.dueMessages(listOf(s),time,plan(s),now,zone).single()
        assertEquals(listOf(0L,1L,3L,7L),message.reasons.map { it.daysRemaining })
        assertEquals(4,message.coveredKeys.size)
    }
    @Test fun reminderHorizonIsHalfOpenEvenWhenDueIsOutsideIt() {
        val events = plan()
        assertTrue(events.any { it.targetDate == day.plusDays(13) && it.offsetDays == 7 })
        assertFalse(events.any { it.plannedLocalDateTime.toLocalDate() == day.plusDays(7) })
    }
    @Test fun pastTimeOnCreationDoesNotInventCatchup() {
        assertEquals(24,plan(at = now.plusSeconds(1)).size)
        assertTrue(rules.dueMessages(listOf(input()),time,emptyList(),now.plusSeconds(1),zone).isEmpty())
    }
    @Test fun weeklyEnumeratesFutureDueWhoseOffsetIsInsideHorizon() {
        val events = plan(input(FrequencyUnit.WEEK))
        assertEquals(4,events.size)
        assertTrue(events.any { it.targetDate == day.plusDays(7) && it.offsetDays == 7 })
    }
    @Test fun customShortIntervalDoesNotGenerateBeforeSeriesStart() {
        val s = input(count = 2,anchor = day.plusDays(4))
        val events = plan(s)
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.targetDate >= day.plusDays(4) && java.time.temporal.ChronoUnit.DAYS.between(day.plusDays(4),it.targetDate) % 2L == 0L })
    }
    @Test fun recoveryCombinesPreviouslyPersistedCyclesOnceAndUsesActualRemainingDays() {
        val s = input(anchor = day.minusDays(20))
        val persisted = plan(s,now.minusSeconds(6*86400))
        val message = rules.dueMessages(listOf(s),time,persisted,now.plusSeconds(3600),zone).single()
        assertTrue(message.reasons.size > 1)
        assertTrue(message.reasons.all { it.targetDate >= day && it.daysRemaining == java.time.temporal.ChronoUnit.DAYS.between(day,it.targetDate) })
        assertEquals(message.reasons.size,message.reasons.map { it.targetDate }.distinct().size)
        assertTrue(rules.dueMessages(listOf(s),time,persisted,now.plusSeconds(3600),zone,message.coveredKeys).isEmpty())
    }
    @Test fun longAbsenceDoesNotEnumerateHistoryOrRecoverExpiredDue() {
        val s = input(anchor = LocalDate.of(1900,1,1))
        assertEquals(28,plan(s).size)
        val old = plan(s,now.minusSeconds(20*86400))
        assertTrue(rules.dueMessages(listOf(s),time,old,now,zone).isEmpty())
    }
    @Test fun emptyOffsetsAndInactiveStatesHaveNoPlan() {
        assertTrue(plan(input(offsets = emptySet())).isEmpty())
        for (state in listOf(SubscriptionState.CANCELLED,SubscriptionState.ARCHIVED)) {
            val s = input(); assertTrue(plan(s.copy(subscription = s.subscription.copy(state = state))).isEmpty())
        }
    }
    @Test fun handledKeysSurviveClockRollbackAndOffsetToggle() {
        val s = input(); val events = plan(s)
        val keys = rules.dueMessages(listOf(s),time,events,now,zone).single().coveredKeys
        assertTrue(rules.dueMessages(listOf(s),time,events,now,zone,keys).isEmpty())
        assertTrue(rules.dueMessages(listOf(s.copy(renewalOffsets = emptySet())),time,events,now,zone).isEmpty())
        assertTrue(rules.dueMessages(listOf(s),time,events,now.minusSeconds(3600),zone,keys).isEmpty())
    }

    @Test fun recoveryMetadataHasProvenFifteenOccurrenceBoundWithoutTruncation() {
        val s = input(anchor = day.minusYears(100))
        val persisted = (1L..7L).flatMap { plan(s,now.minusSeconds(it*86400)) } + plan(s)
        val message = rules.dueMessages(listOf(s),time,persisted,now.plusSeconds(1),zone).single()
        assertEquals(15,message.coveredKeys.size)
        assertEquals(8,message.reasons.size)
        assertEquals(ReminderRules.MAX_MESSAGE_OCCURRENCES,message.coveredKeys.size)
    }
    @Test fun unrepresentableSeriesIsReportedWithoutHidingHealthySubscription() {
        val bad = input(FrequencyUnit.YEAR,Int.MAX_VALUE,LocalDate.MIN,id = 2)
        val projection = rules.currentCandidates(listOf(input(),bad),time,day)
        assertEquals(setOf(bad.subscription.id),projection.failedSubscriptions)
        assertEquals(28,plan().size)
        assertEquals(plan(),projection.events.filter { it.plannedLocalDateTime.toLocalDate() >= day })
    }
    @Test fun financialOverflowDoesNotParticipateInReminderProjection() {
        val s = input(FrequencyUnit.MONTH).let { it.copy(subscription = it.subscription.copy(price = RubPrice(Long.MAX_VALUE))) }
        assertThrows(ArithmeticException::class.java) {
            com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine().calculate(listOf(s.subscription),day)
        }
        assertEquals(1,plan(s).size)
        assertTrue(rules.eligibleForPermission(s,day))
    }
    @Test fun permissionEligibilityDoesNotDependOnSevenDayHorizon() {
        val s = input(FrequencyUnit.YEAR,anchor = day.plusMonths(6))
        assertTrue(plan(s).isEmpty())
        assertTrue(rules.eligibleForPermission(s,day))
        assertFalse(rules.eligibleForPermission(s.copy(renewalOffsets = emptySet()),day))
    }
    @Test fun priceNameAndAppearanceChangesKeepLogicalEventsAndUseIndependentIds() {
        val a = input(); val b = input(id = 2)
        val changed = a.copy(subscription = a.subscription.copy(name = "Новое синтетическое имя",price = RubPrice(456),
            appearance = SubscriptionAppearance(catalogLogoId = "other")))
        assertEquals(plan(a),plan(changed))
        assertEquals(56,rules.plan(listOf(a,b),time,now,zone).map { it.key }.distinct().size)
    }
    @Test fun explicitScheduleChangeInvalidatesOldKeys() {
        val s = input(FrequencyUnit.WEEK); val old = plan(s)
        val changed = s.copy(subscription = s.subscription.copy(schedule = RecurringSchedule.starting(day.plusDays(1),Frequency(FrequencyUnit.WEEK,1))))
        assertTrue(old.none { rules.matchesCurrentSchedule(it,listOf(changed),time,now,zone) })
    }
    @Test fun zoneChangesInstantsButNeverDueDatesOrLogicalKeys() {
        val s = input(); val a = rules.plan(listOf(s),time,now.minusSeconds(86400),ZoneId.of("Europe/Amsterdam"))
        val b = rules.plan(listOf(s),time,now.minusSeconds(86400),ZoneId.of("Asia/Tokyo"))
        assertEquals(a,b)
        assertNotEquals(rules.resolveLocal(a.first().plannedLocalDateTime,ZoneId.of("Europe/Amsterdam")).toInstant(),
            rules.resolveLocal(b.first().plannedLocalDateTime,ZoneId.of("Asia/Tokyo")).toInstant())
    }
    @Test fun hundredDailySubscriptionsStressIsCompleteDeterministicAndBounded() {
        val inputs = (1L..100L).map { input(anchor = LocalDate.of(1900,1,1),id = it) }
        val start = System.nanoTime()
        val first = rules.plan(inputs,time,now,zone)
        val repeat = rules.plan(inputs.reversed(),time,now,zone)
        val elapsed = (System.nanoTime()-start)/1_000_000.0
        assertEquals(2800,first.size); assertEquals(first,repeat)
        assertEquals(100,first.groupBy { it.subscriptionId }.size)
        println("R2b synthetic JVM: events=${first.size}, subscription task groups=100, two projections ms=$elapsed")
    }

    @Test fun quarterlyHalfYearAndLeapRenewalsUseCalendarBoundaries() {
        for ((count,target) in listOf(3 to LocalDate.of(2027,4,30),6 to LocalDate.of(2027,7,31))) {
            val s = input(FrequencyUnit.MONTH,count,LocalDate.of(2027,1,31),setOf(0))
            val result = plan(s,target.minusDays(6).atStartOfDay().toInstant(zone))
            assertEquals(target,result.single().targetDate)
            assertEquals(31,s.subscription.schedule.anchor.day)
        }
        val leap = input(FrequencyUnit.YEAR,1,LocalDate.of(2024,2,29),setOf(0))
        for (target in listOf(LocalDate.of(2027,2,28),LocalDate.of(2028,2,29))) {
            assertEquals(target,plan(leap,target.minusDays(6).atStartOfDay().toInstant(zone)).single().targetDate)
        }
    }
}
