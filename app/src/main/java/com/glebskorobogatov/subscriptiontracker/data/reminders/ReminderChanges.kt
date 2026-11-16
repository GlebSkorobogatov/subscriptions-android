package com.glebskorobogatov.subscriptiontracker.data.reminders

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by production writers and background snapshots. No scope or cached user data. */
class ReminderChanges(val gate: Mutex = Mutex(), private val validate: () -> Unit = {}, private val request: suspend () -> Unit = {}) {
    suspend fun <T> write(shouldNotify: (T) -> Boolean = { true }, action: suspend () -> T): T = gate.withLock {
        validate() // Recheck generation after acquiring the shared mutation gate.
        val result = action()
        // A committed payment/edit must not be reported as failed because enqueue failed.
        withContext(NonCancellable) {
            try { if (shouldNotify(result)) withTimeout(5_000) { request() } }
            catch (_: Exception) { /* Durable WorkManager wake / next app start repairs enqueue. */ }
        }
        result
    }
    companion object {
        private val gates = ConcurrentHashMap<String, Mutex>()
        fun gate(path: String): Mutex = gates.getOrPut(path) { Mutex() }
    }
}

/** Serialize the entire read/apply cycle across runtime instances. The mutation gate stays short:
 * a writer can commit and queue its successor while an older WorkManager apply is in progress. */
object ReminderReconciliation {
    suspend fun <T> run(datasetPath: String, snapshot: suspend () -> T, apply: suspend (T) -> Unit) {
        ReminderChanges.gate("reconcile:$datasetPath").withLock { apply(snapshot()) }
    }
}
