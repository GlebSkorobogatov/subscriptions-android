package com.glebskorobogatov.subscriptiontracker.data.room

/** Safe diagnostics only: never retain SQLite messages, paths, SQL or exception causes. */
enum class StorageFailure(val retryable: Boolean) {
    LOST_DATABASE(false), INITIALIZATION_UNCERTAIN(false), INCOMPATIBLE(false), CORRUPT(false), INVALID_POINTER(false), TEMPORARY(true), UNKNOWN(false), STALE_GENERATION(false)
}
class StorageUnavailable(val failure: StorageFailure) : IllegalStateException("Storage unavailable: ${failure.name}")

/** Shared by foreground retry and WorkManager; permanent failures never loop. */
fun StorageFailure.retryWorker(attempt: Int): Boolean = retryable && attempt < 2
