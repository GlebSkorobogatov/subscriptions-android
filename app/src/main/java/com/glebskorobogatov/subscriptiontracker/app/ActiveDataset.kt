package com.glebskorobogatov.subscriptiontracker.app

import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** Only the pointer lives here. Dataset switching and recovery belong to stage H. */
class ActiveDataset(private val directory: File) {
    private val pointer = AtomicFile(File(directory, "active-generation.pointer"))

    fun getOrCreate(): UUID = synchronized(lock) {
        // openRead restores AtomicFile's legacy backup if present.
        if (pointer.baseFile.exists() || File(pointer.baseFile.path + ".bak").exists()) {
            val text = pointer.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            val id = try { UUID.fromString(text).also { require(it.toString() == text) } }
            catch (_: IllegalArgumentException) { throw com.glebskorobogatov.subscriptiontracker.data.room.StorageUnavailable(
                com.glebskorobogatov.subscriptiontracker.data.room.StorageFailure.INVALID_POINTER) }
            id
        } else {
            // Only a genuinely new install can create a generation. Never hide orphaned data.
            fun children(file: File): List<File> = if (!file.exists()) emptyList() else
                (file.listFiles() ?: throw java.io.IOException("Cannot inspect retained storage")).toList()
            val retained = File(pointer.baseFile.path + ".new").exists() ||
                children(File(directory, "assets")).isNotEmpty() ||
                children(File(directory, "datastore")).any { it.name.startsWith("settings-") } ||
                children(File(directory.parentFile, "databases")).any { it.name.startsWith("subscriptions-") }
            if (retained) throw com.glebskorobogatov.subscriptiontracker.data.room.StorageUnavailable(
                com.glebskorobogatov.subscriptiontracker.data.room.StorageFailure.INVALID_POINTER)
            val id = UUID.randomUUID()
            val output = pointer.startWrite()
            try {
                output.write(id.toString().toByteArray(Charsets.UTF_8))
                pointer.finishWrite(output)
            } catch (error: Exception) {
                pointer.failWrite(output)
                throw error
            }
            id
        }
    }

    /** The future dataset switch must use this same guard around pointer replacement.
     * Keep the external action short and synchronous; never suspend under this monitor.
     * Callers acquire the dataset mutation gate before this guard, then the Room transaction.
     * Future H cleanup must join old readers outside the guard before deleting their files. */
    fun <T> ifCurrent(expected: UUID, action: () -> T): T? = synchronized(lock) {
        if (getOrCreate() == expected) action() else null
    }

    companion object {
        private val lock = Any()
        internal fun <T> guarded(expected: UUID, current: () -> UUID, action: () -> T): T? = synchronized(lock) {
            if (current() == expected) action() else null
        }
    }
}
