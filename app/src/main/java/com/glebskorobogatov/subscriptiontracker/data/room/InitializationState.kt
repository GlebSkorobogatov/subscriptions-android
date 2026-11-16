package com.glebskorobogatov.subscriptiontracker.data.room

import java.security.MessageDigest
import java.util.UUID

/** AUTHORIZED is a one-use creation right; CREATING never permits a second missing-file creation. */
internal enum class InitializationState { AUTHORIZED, CREATING, READY }
internal object InitializationRecord {
    fun encode(id: UUID, state: InitializationState): String {
        val payload = "1|$id|${state.name}"
        return "$payload|${digest(payload)}"
    }
    fun decode(id: UUID, text: String): InitializationState {
        val parts = text.split('|')
        if (parts.size != 4 || parts[0] != "1" || parts[1] != id.toString() ||
            parts[3] != digest(parts.take(3).joinToString("|"))) throw StorageUnavailable(StorageFailure.INITIALIZATION_UNCERTAIN)
        return InitializationState.entries.firstOrNull { it.name == parts[2] }
            ?: throw StorageUnavailable(StorageFailure.INITIALIZATION_UNCERTAIN)
    }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun mayCreate(state: InitializationState?): Boolean = when (state) {
        InitializationState.AUTHORIZED -> true
        InitializationState.READY -> throw StorageUnavailable(StorageFailure.LOST_DATABASE)
        else -> throw StorageUnavailable(StorageFailure.INITIALIZATION_UNCERTAIN)
    }
}
