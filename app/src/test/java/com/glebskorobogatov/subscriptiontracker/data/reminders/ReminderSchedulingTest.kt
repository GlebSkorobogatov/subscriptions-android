package com.glebskorobogatov.subscriptiontracker.data.reminders

import com.glebskorobogatov.subscriptiontracker.domain.model.SubscriptionId
import com.glebskorobogatov.subscriptiontracker.domain.reminders.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

class ReminderSchedulingTest {
    private val id = UUID(0, 1)
    private val generation = UUID(0, 2)
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val now = Instant.parse("2027-01-01T00:00:00Z")
    private fun event(offset: Int = 3, time: LocalTime = LocalTime.of(9, 0)) = PlannedReminder(
        SubscriptionId(id), ReminderEventType.PAYMENT, LocalDate.of(2027,1,5), offset,
        LocalDate.of(2027,1,5).minusDays(offset.toLong()).atTime(time))
    @Test fun unchangedInputsProduceSameWorkIdentity() {
        val events = listOf(event(), event(1))
        val hash = ReminderScheduling.fingerprint(events, zone)
        assertEquals(hash, ReminderScheduling.fingerprint(events.reversed() + events, zone))
        assertEquals(ReminderScheduling.workName(generation,id,hash,now),ReminderScheduling.workName(generation,id,hash,now))
    }
    @Test fun generationsHaveIndependentNamesAndPublicationTags() {
        assertNotEquals(ReminderScheduling.identity(generation,id),ReminderScheduling.identity(UUID(0,3),id))
        assertNotEquals(ReminderScheduling.identity(generation,id),ReminderScheduling.identity(generation,UUID(0,3)))
    }
    @Test fun zoneChangeChangesPlanButNotLogicalKey() {
        assertNotEquals(ReminderScheduling.fingerprint(listOf(event()),zone),ReminderScheduling.fingerprint(listOf(event()),ZoneId.of("Asia/Yekaterinburg")))
        assertEquals(event().key,event(time = LocalTime.NOON).key)
    }
    @Test fun changingLocalTimeChangesPlan() {
        assertNotEquals(ReminderScheduling.fingerprint(listOf(event()),zone),ReminderScheduling.fingerprint(listOf(event(time = LocalTime.NOON)),zone))
    }
    @Test fun changingTargetChangesPlan() {
        val first = event()
        val moved = first.copy(targetDate = first.targetDate.plusDays(1), plannedLocalDateTime = first.plannedLocalDateTime.plusDays(1))
        assertNotEquals(ReminderScheduling.fingerprint(listOf(first),zone),ReminderScheduling.fingerprint(listOf(moved),zone))
    }
    @Test fun nearestFutureSelectedInResolvedZone() {
        assertEquals(Instant.parse("2027-01-02T08:00:00Z"),ReminderScheduling.next(listOf(event(1),event()),now,zone,false))
    }
    @Test fun unavailablePublisherDoesNotImmediatelyRetryPastCause() {
        assertFalse(UnavailableReminderPublisher.available)
        assertTrue(ReminderPublisher { _, _ -> PublicationResult.PUBLISHED }.available)
        val late = Instant.parse("2027-01-03T12:00:00Z")
        assertEquals(Instant.parse("2027-01-04T08:00:00Z"),ReminderScheduling.next(listOf(event(),event(1)),late,zone,false))
        assertEquals(Instant.parse("2027-01-02T08:00:00Z"),ReminderScheduling.next(listOf(event(),event(1)),late,zone,true))
    }
    @Test fun noEventsMeansNoDeliveryWork() { assertNull(ReminderScheduling.next(emptyList(),now,zone,true)) }
    @Test fun futureSlotHasDistinctNameWhilePriorWorkerRuns() {
        val hash = ReminderScheduling.fingerprint(listOf(event()),zone)
        assertNotEquals(ReminderScheduling.workName(generation,id,hash,now),ReminderScheduling.workName(generation,id,hash,now.plusSeconds(60)))
    }
    @Test fun invalidFingerprintRejected() {
        assertThrows(IllegalArgumentException::class.java) { ReminderScheduling.workName(generation,id,"not-a-versioned-hash",now) }
    }
    @Test fun wakeFollowsLocalCalendarAndYearRollover() {
        assertEquals(Instant.parse("2027-01-01T23:05:00Z"),ReminderScheduling.nextWake(now,zone))
        assertEquals(Instant.parse("2028-01-01T00:05:00Z"),ReminderScheduling.nextWake(Instant.parse("2027-12-31T23:59:00Z"),ZoneOffset.UTC))
    }
    @Test fun wakeUsesDstZoneRatherThanFixedOffset() {
        assertEquals(Instant.parse("2027-03-28T22:05:00Z"),ReminderScheduling.nextWake(Instant.parse("2027-03-28T12:00:00Z"),zone))
    }
    @Test fun mutationNotifiesOnlyAfterSuccessfulCommit() = runTest {
        val calls = mutableListOf<String>()
        val changes = ReminderChanges(request = { calls += "enqueue" })
        changes.write { calls += "commit" }
        assertEquals(listOf("commit","enqueue"),calls)
        try { changes.write<Unit> { error("rollback") } } catch (_: IllegalStateException) { }
        assertEquals(2,calls.size)
    }
    @Test fun enqueueFailureDoesNotLieAboutCommittedMutation() = runTest {
        assertEquals(42,ReminderChanges(request = { error("WorkManager unavailable") }).write { 42 })
    }
    @Test fun unrelatedSettingsDoNotSignalReconciliation() = runTest {
        var calls = 0
        ReminderChanges(request = { calls++ }).write(shouldNotify = { it }) { false }
        assertEquals(0,calls)
    }
    @Test fun writersAndSnapshotsUseSameGate() = runTest {
        assertSame(ReminderChanges.gate("test-dataset"),ReminderChanges.gate("test-dataset"))
        val gate = ReminderChanges.gate("isolated-writer")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val values = mutableListOf<String>()
        val first = launch { ReminderChanges(gate).write { values += "first"; entered.complete(Unit); release.await() } }
        entered.await()
        val second = launch { ReminderChanges(gate).write { values += "second" } }
        yield(); assertEquals(listOf("first"),values)
        release.complete(Unit); joinAll(first,second)
        assertEquals(listOf("first","second"),values)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun olderSnapshotCannotOverwriteNewerReconciliationAfterConcurrentMutation() = runTest {
        var desired = 1; var applied = 0; var reads = 0
        val captured = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val old = launch {
            ReminderReconciliation.run("isolated-reconcile-order", snapshot = { reads++; desired }, apply = {
                captured.complete(Unit); release.await(); applied = it
            })
        }
        captured.await(); desired = 2
        val current = launch {
            ReminderReconciliation.run("isolated-reconcile-order", snapshot = { reads++; desired }, apply = { applied = it })
        }
        runCurrent()
        val readsWhileOldApplyPending = reads
        release.complete(Unit); joinAll(old,current)
        assertEquals(1,readsWhileOldApplyPending)
        assertEquals(2,reads); assertEquals(2,applied)
    }

}
