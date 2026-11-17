package com.glebskorobogatov.subscriptiontracker.app

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

/**
 * Instrumentation-only gate in front of the UI existence read.
 * Release builds do not contain this type. Production navigation never references it.
 */
object NavigationLookupPause {
    const val EXTRA = "pause_navigation_lookup"

    @Volatile private var gate: CompletableDeferred<Unit>? = null
    private val entries = AtomicInteger(0)

    fun arm(): CompletableDeferred<Unit> {
        gate?.complete(Unit)
        entries.set(0)
        return CompletableDeferred<Unit>().also { gate = it }
    }

    fun entered(): Int = entries.get()

    suspend fun awaitTurn() {
        val pending = gate ?: return
        entries.incrementAndGet()
        pending.await()
    }

    fun reset() {
        gate?.complete(Unit)
        gate = null
        entries.set(0)
    }
}
