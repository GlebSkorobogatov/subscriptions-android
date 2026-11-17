package com.glebskorobogatov.subscriptiontracker.domain.reminders

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class ReminderRulesTest {
    private val rules = ReminderRules()
    private val utc = ZoneId.of("UTC")
    private val nine = LocalTime.of(9,0)
    private val start = Instant.parse("2027-01-01T00:00:00Z")
    private fun d(text: String) = LocalDate.parse(text)
    private fun input(id: Long = 1,due: LocalDate = d("2027-01-31"),
        pay: Set<Int> = setOf(3,1),
        frequency: Frequency = Frequency(FrequencyUnit.MONTH,1)) = ReminderSubscription(
        SubscriptionRecord(SubscriptionId(UUID(0,id)),"Сервис",RubPrice(100),RecurringSchedule(RenewalAnchor.from(due),frequency)),pay)
    private fun plan(s: ReminderSubscription = input(),now: Instant = start,time: LocalTime = nine,zone: ZoneId = utc) = rules.plan(listOf(s),time,now,zone)

    @Test fun onlyRenewalDatesUseTheGlobalOffsets() {
        val items = plan()
        assertEquals(listOf(d("2027-01-28"),d("2027-01-30")),items.map { it.plannedLocalDateTime.toLocalDate() })
        assertTrue(items.all { it.eventType == ReminderEventType.RENEWAL && it.targetDate == d("2027-01-31") })
        assertTrue(items.all { it.plannedLocalDateTime.toLocalTime() == nine })
    }

    @Test fun emptyOffsetsDisableRenewalsAndNoCancellationEventExists() {
        assertTrue(plan(input(pay = emptySet())).isEmpty())
        assertEquals(listOf(ReminderEventType.RENEWAL),ReminderEventType.entries)
    }

    @Test fun zeroIsInclusiveAtNowButNeverRescheduledAfterConfiguredTime() {
        val s = input(pay = setOf(0)); val now = Instant.parse("2027-01-31T09:00:00Z")
        val pending = plan(s,now); assertEquals(1,pending.size)
        assertTrue(plan(s,now.plusSeconds(1)).isEmpty())
        val messages = rules.dueMessages(listOf(s),nine,pending,now.plusSeconds(1),utc)
        assertEquals(0L,messages.single().reasons.single().daysRemaining)
    }
    @Test fun initialSetupNeverInventsBackdatedWarningsOrRecovery() {
        val now = Instant.parse("2027-01-30T10:00:00Z"); val s = input()
        assertTrue(plan(s,now).isEmpty())
        assertTrue(rules.dueMessages(listOf(s),nine,emptyList(),now,utc).isEmpty())
    }
    @Test fun changingDateAndTimeInvalidatesOldPayloadButTimeKeepsLogicalKey() {
        val s = input(); val before = plan(s)
        val atTen = plan(s,time = LocalTime.of(10,0))
        assertEquals(before.map { it.key },atTen.map { it.key })
        assertFalse(rules.matchesCurrentSchedule(before.first(),listOf(s),LocalTime.of(10,0),start,utc))
        val moved = s.copy(subscription = s.subscription.copy(schedule = RecurringSchedule.starting(d("2027-01-30"),s.subscription.schedule.frequency)))
        assertNotEquals(before.map { it.key }.toSet(),plan(moved).map { it.key }.toSet())
        assertFalse(rules.matchesCurrentSchedule(before.first(),listOf(moved),nine,start,utc))
    }
    @Test fun zoneChangePreservesNineCalendarDueAnchorAndKeysButChangesInstant() {
        val s = input(); val a = ZoneId.of("Europe/Amsterdam"); val b = ZoneId.of("Asia/Yekaterinburg")
        val x = plan(s,zone = a); val y = plan(s,zone = b)
        assertEquals(x,y); assertEquals(s.subscription.schedule.anchor,RenewalAnchor.from(d("2027-01-31")))
        assertEquals(nine,rules.resolveLocal(x.first().plannedLocalDateTime,b).toLocalTime())
        assertNotEquals(rules.resolveLocal(x.first().plannedLocalDateTime,a).toInstant(),rules.resolveLocal(x.first().plannedLocalDateTime,b).toInstant())
        assertEquals(Duration.ofHours(4),Duration.between(rules.resolveLocal(x.first().plannedLocalDateTime,b).toInstant(),rules.resolveLocal(x.first().plannedLocalDateTime,a).toInstant()))
    }
    @Test fun amsterdamSpringGapUsesFirstValidTimeWithoutChangingLogicalDateOrTime() {
        val local = LocalDateTime.parse("2027-03-28T02:30"); val zone = ZoneId.of("Europe/Amsterdam")
        val resolved = rules.resolveLocal(local,zone)
        assertEquals(LocalDateTime.parse("2027-03-28T03:00"),resolved.toLocalDateTime())
        assertEquals(Instant.parse("2027-03-28T01:00:00Z"),resolved.toInstant())
        val s = input(due = local.toLocalDate(),pay = setOf(0))
        val p = plan(s,Instant.parse("2027-03-28T00:59:59Z"),local.toLocalTime(),zone).single()
        assertEquals(local,p.plannedLocalDateTime); assertEquals(local.toLocalDate(),p.targetDate)
        assertTrue(plan(s,resolved.toInstant().plusSeconds(1),local.toLocalTime(),zone).isEmpty())
    }
    @Test fun amsterdamAutumnOverlapChoosesEarlierOffsetAndOneLogicalOccurrence() {
        val local = LocalDateTime.parse("2027-10-31T02:30"); val zone = ZoneId.of("Europe/Amsterdam")
        val resolved = rules.resolveLocal(local,zone)
        assertEquals(ZoneOffset.ofHours(2),resolved.offset); assertEquals(Instant.parse("2027-10-31T00:30:00Z"),resolved.toInstant())
        val s = input(due = local.toLocalDate(),pay = setOf(0))
        val pending = plan(s,Instant.parse("2027-10-30T00:00:00Z"),local.toLocalTime(),zone)
        assertEquals(1,pending.size)
        val sent = rules.dueMessages(listOf(s),local.toLocalTime(),pending,resolved.toInstant(),zone).single()
        assertTrue(rules.dueMessages(listOf(s),local.toLocalTime(),pending,resolved.toInstant().plusSeconds(3600),zone,sent.coveredKeys).isEmpty())
    }
    @Test fun duplicateOccurrencesRetainOneRenewalReasonInOneMessage() {
        val s = input(pay = setOf(3)); val pending = plan(s)
        val message = rules.dueMessages(listOf(s),nine,pending+pending,Instant.parse("2027-01-28T09:00:00Z"),utc).single()
        assertEquals(1,message.reasons.size); assertEquals(1,message.coveredKeys.size)
        assertEquals(listOf(3L),message.reasons.map { it.daysRemaining })
    }

    @Test fun longShutdownCollapsesThreeMissedOffsetsIntoOneCurrentReason() {
        val s = input(pay = setOf(7,3,1)); val pending = plan(s)
        val now = Instant.parse("2027-01-30T12:00:00Z")
        assertTrue(plan(s,now).isEmpty())
        val messages = rules.dueMessages(listOf(s),nine,pending,now,utc)
        assertEquals(1,messages.size); assertEquals(1,messages.single().reasons.size)
        val reason = messages.single().reasons.single()
        assertEquals(1L,reason.daysRemaining); assertEquals(3,reason.occurrences.size); assertEquals(3,messages.single().coveredKeys.size)
        assertEquals(messages,rules.dueMessages(listOf(s),nine,pending,now,utc))
        assertTrue(rules.dueMessages(listOf(s),nine,pending,now,utc,messages.single().coveredKeys).isEmpty())
    }
    @Test fun futureOccurrencesAreNotRecoveryCandidates() {
        val s = input(pay = setOf(7)); val pending = plan(s)
        assertTrue(rules.dueMessages(listOf(s),nine,pending,Instant.parse("2027-01-23T12:00:00Z"),utc).isEmpty())
    }
    @Test fun rolledTargetsCannotResurrectOldWarnings() {
        val now = Instant.parse("2027-02-01T12:00:00Z")
        run {
            val s = input(); val old = plan(s)
            assertTrue(rules.dueMessages(listOf(s),nine,old,now,utc).isEmpty())
            assertTrue(old.none { rules.matchesCurrentSchedule(it,listOf(s),nine,now,utc) })
        }
    }
    @Test fun excludedLifecycleStatesAndDeletedSubscriptionsHaveNoReminders() {
        val original = input(); val pending = plan(original)
        for(state in listOf(SubscriptionState.CANCELLED,SubscriptionState.ARCHIVED)) {
            val s = original.copy(subscription = original.subscription.copy(state = state))
            assertTrue(plan(s).isEmpty()); assertTrue(rules.dueMessages(listOf(s),nine,pending,Instant.parse("2027-01-30T12:00:00Z"),utc).isEmpty())
        }
        assertTrue(rules.dueMessages(emptyList(),nine,pending,Instant.parse("2027-01-30T12:00:00Z"),utc).isEmpty())
    }


    @Test fun monthlyAnchorReturnsFromFebruaryToMarch() {
        val auto = input(pay = setOf(0))
        assertEquals(d("2027-02-28"),plan(auto,Instant.parse("2027-02-01T00:00:00Z")).single().targetDate)
        assertEquals(d("2027-03-31"),plan(auto,Instant.parse("2027-03-01T00:00:00Z")).single().targetDate)
    }
    @Test fun leapAnchorIsPreservedForFutureLeapYears() {
        val s = input(due = d("2028-02-29"),pay = setOf(0),frequency = Frequency(FrequencyUnit.YEAR,1))
        assertEquals(d("2029-02-28"),plan(s,Instant.parse("2029-01-01T00:00:00Z")).single().targetDate)
        assertEquals(d("2032-02-29"),plan(s,Instant.parse("2032-01-01T00:00:00Z")).single().targetDate)
    }

    @Test fun customDayWeekAndCalendarMonthIntervalsUseDomainCalendar() {
        for((frequency,expected) in listOf(Frequency(FrequencyUnit.DAY,3) to "2027-02-03",Frequency(FrequencyUnit.WEEK,2) to "2027-02-14",Frequency(FrequencyUnit.MONTH,3) to "2027-04-30")) {
            val s = input(pay = setOf(0),frequency = frequency)
            assertEquals(d(expected),plan(s,Instant.parse("2027-02-01T00:00:00Z")).single().targetDate)
        }
    }
    @Test fun deterministicOrderingStableIdsAndIndependentUuidsExcludeLogicalDuplicates() {
        val a = input(); val b = input(id = 2)
        val once = rules.plan(listOf(a,b),nine,start,utc)
        assertEquals(once,rules.plan(listOf(b,a,a),nine,start,utc))
        assertEquals(once.size,once.map { it.key.stableId }.distinct().size)
        assertEquals("reminder:v1|00000000-0000-0000-0000-000000000001|RENEWAL|2027-01-31|3",once.first().key.stableId)
        assertEquals(once,rules.plan(listOf(a.copy(renewalOffsets = linkedSetOf(1,3)),b),nine,start,utc))
        assertEquals(2,rules.dueMessages(listOf(a,b),nine,once,Instant.parse("2027-01-30T12:00:00Z"),utc).size)
    }
    @Test fun invalidOffsetsLeadConflictingSnapshotsAndMismatchedDatesFailExplicitly() {
        assertThrows(IllegalArgumentException::class.java) { input(pay = setOf(2)) }
        val s = input()
        assertThrows(IllegalArgumentException::class.java) { rules.plan(listOf(s,s.copy(renewalOffsets = setOf(0))),nine,start,utc) }
        val mutable = mutableSetOf(3); val changed = input(pay = mutable); mutable += 99
        assertThrows(IllegalArgumentException::class.java) { plan(changed) }
        val valid = plan(s).first()
        assertThrows(IllegalArgumentException::class.java) { valid.copy(plannedLocalDateTime = valid.plannedLocalDateTime.plusDays(1)) }
        assertThrows(IllegalArgumentException::class.java) { valid.key.copy(offsetDays = -1) }
    }
    @Test fun calendarUnderflowAndOutOfRangeInstantDoNotReturnPartialPlans() {
        val min = input(id = 2,due = LocalDate.MIN,pay = setOf(7))
        val now = LocalDate.MIN.atStartOfDay(utc).toInstant()
        assertThrows(ReminderCalculationException::class.java) { rules.plan(listOf(input(),min),nine,now,utc) }
        assertThrows(ReminderCalculationException::class.java) { plan(now = Instant.MAX) }
    }
    @Test fun delayedReasonsCrossLocalMidnightAndYearWithoutResurrectingHandledKeys() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val s = input(due = d("2028-01-01"),pay = setOf(3,1))
        val saved = plan(s,Instant.parse("2027-12-28T00:00:00Z"),zone = zone)
        val before = rules.dueMessages(listOf(s),nine,saved,Instant.parse("2027-12-31T22:59:00Z"),zone).single()
        assertEquals(setOf(1L),before.reasons.map { it.daysRemaining }.toSet())
        val midnight = Instant.parse("2027-12-31T23:00:00Z") // 01 January locally, still December in UTC.
        val current = rules.dueMessages(listOf(s),nine,saved,midnight,zone).single()
        assertEquals(d("2028-01-01"),current.asOfLocalDate)
        assertEquals(setOf(0L),current.reasons.map { it.daysRemaining }.toSet())
        assertEquals(before.coveredKeys,current.coveredKeys); assertEquals(2,current.coveredKeys.size)
        assertTrue(rules.dueMessages(listOf(s),nine,saved,midnight,zone,current.coveredKeys).isEmpty())
        assertTrue(rules.dueMessages(listOf(s),nine,saved,midnight.minusSeconds(3600),zone,current.coveredKeys).isEmpty())
        assertTrue(rules.dueMessages(listOf(s),nine,saved,Instant.parse("2028-01-01T23:00:00Z"),zone).isEmpty())
    }

}
