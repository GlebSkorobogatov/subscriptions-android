package com.glebskorobogatov.subscriptiontracker.ui.navigation

import com.glebskorobogatov.subscriptiontracker.notifications.NotificationNavigation
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationNavigationResolverTest {
    private val generation = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val first = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val second = UUID.fromString("33333333-3333-4333-8333-333333333333")

    @Test fun delayedLookupStillReachesDetailsAndConsumesOnlyAfterwards(): Unit = runTest {
        val gate = CompletableDeferred<Boolean>()
        val events = mutableListOf<String>()
        var current: NotificationNavigation? = request(1, first)
        val session = NotificationNavigationSession()
        session.dispatch(this, current?.sequence, editor = false) {
            val decision = resolve(current!!, exists = { events += "read"; gate.await() })
            apply(current!!, decision, latest = { current?.sequence }, events = events) { sequence ->
                events += "consume:$sequence"
                current = null
                session.dispatch(this, null, editor = false) {}
            }
        }
        runCurrent()
        assertEquals(listOf("read"), events)
        gate.complete(true)
        runCurrent()
        assertEquals(listOf("read", "details:$first", "consume:1"), events)
        assertNull(current)
    }

    @Test fun newerRequestDuringLookupIsKeptAndTheStaleOneDoesNotNavigate(): Unit = runTest {
        val gate = CompletableDeferred<Boolean>()
        val events = mutableListOf<String>()
        var current = request(1, first)
        val session = NotificationNavigationSession()
        fun consume(sequence: Long) {
            events += "consume:$sequence"
            if (current.sequence == sequence) current = request(current.sequence, current.subscription)
        }
        session.dispatch(this, 1, editor = false) {
            val decision = resolve(request(1, first), exists = { gate.await() }, latest = { current.sequence })
            apply(request(1, first), decision, latest = { current.sequence }, events = events, consume = ::consume)
        }
        runCurrent()
        current = request(2, second)
        session.dispatch(this, 2, editor = false) {
            val decision = resolve(current, exists = { events += "read-2"; true }, latest = { current.sequence })
            apply(current, decision, latest = { current.sequence }, events = events, consume = ::consume)
        }
        gate.complete(true)
        runCurrent()
        assertEquals(listOf("read-2", "details:$second", "consume:2"), events)
    }

    @Test fun staleCompletionCannotNavigateOrClearANewerSequence(): Unit = runTest {
        val events = mutableListOf<String>()
        val stale = request(1, first)
        val decision = resolve(stale, exists = { true }, latest = { 4L })
        assertEquals(NotificationNavigationDecision.Superseded, decision)
        apply(stale, NotificationNavigationDecision.OpenDetails(first), latest = { 4L }, events = events)
        assertTrue(events.isEmpty())
    }

    @Test fun deletionObservedByTheReadGoesHome(): Unit = runTest {
        var present = true
        val events = mutableListOf<String>()
        val request = request(3, first)
        val decision = resolve(request, exists = {
            delay(5)
            present = false
            present
        })
        assertEquals(NotificationNavigationDecision.Home, decision)
        apply(request, decision, events = events)
        assertEquals(listOf("home", "consume:3"), events)
    }

    @Test fun generationChangeAfterTheReadBlocksTheOpen(): Unit = runTest {
        var active: UUID? = generation
        var reads = 0
        val decision = resolve(request(5, first), activeGeneration = { active }, exists = {
            reads += 1
            active = UUID.randomUUID()
            true
        })
        assertEquals(1, reads)
        assertEquals(NotificationNavigationDecision.Home, decision)
    }

    @Test fun storageFailureIsNotAnOpenedSubscription(): Unit = runTest {
        val events = mutableListOf<String>()
        val request = request(6, first)
        val decision = resolve(request, exists = { error("temporary Room failure") })
        assertEquals(NotificationNavigationDecision.ReadFailed, decision)
        assertEquals(NotificationNavigationEffect.OpenHome, decision.toEffect())
        apply(request, decision, events = events)
        assertEquals(listOf("home", "consume:6"), events)
        assertFalse(events.any { it.startsWith("details:") })
    }

    @Test fun cancellationPropagatesAndDoesNotConsume(): Unit = runTest {
        val gate = CompletableDeferred<Boolean>()
        var consumed = false
        val job = launch {
            resolve(request(7, first), exists = { gate.await() })
            consumed = true
        }
        runCurrent()
        job.cancel()
        gate.complete(true)
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(consumed)
    }

    @Test fun wrappedCancellationIsNotReportedAsAMissingSubscription(): Unit = runTest {
        var decision: NotificationNavigationDecision? = null
        val job = launch {
            decision = resolve(request(8, first), exists = {
                currentCoroutineContext().cancel()
                throw IllegalStateException("Session closed")
            })
        }
        runCurrent()
        assertTrue(job.isCancelled)
        assertNull(decision)
    }

    @Test fun editorWaitsWithoutLookupAndKeepDoesNotConsume(): Unit = runTest {
        var reads = 0
        val events = mutableListOf<String>()
        val request = request(9, first)
        val decision = resolve(request, editor = true, exists = { reads += 1; true })
        assertEquals(0, reads)
        assertEquals(NotificationNavigationDecision.AwaitEditor, decision)
        apply(request, decision, events = events)
        assertEquals(listOf("confirm-exit"), events)
    }

    @Test fun sameVisibleDetailsAreNotPushedAgain(): Unit = runTest {
        val events = mutableListOf<String>()
        val request = request(10, first)
        val decision = resolve(request, visible = { first }, exists = { true })
        assertEquals(NotificationNavigationDecision.AlreadyVisible, decision)
        apply(request, decision, events = events)
        assertEquals(listOf("consume:10"), events)
    }

    @Test fun sequentialRequestsOpenInOrder(): Unit = runTest {
        val opened = mutableListOf<UUID>()
        val session = NotificationNavigationSession()
        for (item in listOf(request(1, first), request(2, second), request(3, first))) {
            val done = CompletableDeferred<Unit>()
            session.dispatch(this, item.sequence, editor = false) {
                val decision = resolve(item, exists = { delay(1); true })
                if (decision is NotificationNavigationDecision.OpenDetails) opened += decision.subscriptionId
                done.complete(Unit)
            }
            done.await()
        }
        assertEquals(listOf(first, second, first), opened)
    }

    @Test fun cancellingTheScopeDuringLookupLeavesTheRequestPending(): Unit = runTest {
        val gate = CompletableDeferred<Boolean>()
        var consumed = false
        val parent = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parent)
        val session = NotificationNavigationSession()
        session.dispatch(scope, 11, editor = false) {
            resolve(request(11, first), exists = { gate.await() })
            consumed = true
        }
        runCurrent()
        parent.cancel()
        gate.complete(true)
        runCurrent()
        assertFalse(consumed)
        val events = mutableListOf<String>()
        val request = request(11, first)
        apply(request, resolve(request, exists = { true }), events = events)
        assertEquals(listOf("details:$first", "consume:11"), events)
    }

    private fun request(sequence: Long, id: UUID?) = NotificationNavigation(sequence, id, generation)

    private suspend fun resolve(
        request: NotificationNavigation,
        editor: Boolean = false,
        visible: () -> UUID? = { null },
        activeGeneration: () -> UUID? = { generation },
        latest: () -> Long? = { request.sequence },
        exists: suspend (UUID) -> Boolean,
    ) = NotificationNavigationResolver.decide(request, editor, visible, activeGeneration, latest, exists)

    private fun apply(
        request: NotificationNavigation,
        decision: NotificationNavigationDecision,
        latest: () -> Long? = { request.sequence },
        events: MutableList<String>,
        consume: (Long) -> Unit = { events += "consume:$it" },
    ) {
        applyNotificationNavigation(
            request, decision, latest,
            navigateHome = { events += "home" },
            navigateDetails = { events += "details:$it" },
            confirmExit = { events += "confirm-exit" },
            consume = consume,
        )
    }
}
