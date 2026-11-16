package com.glebskorobogatov.subscriptiontracker.data.room

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Internal metadata only. Never recovers/replaces an invalid record, and never touches database files.
 * Caller order: dataset mutation gate -> pointer guard -> journal monitor/file lock -> SQLite.
 * No pointer lock is acquired from inside a journal lock. The file lock also serializes processes.
 */
internal class InitializationJournal(files: File, private val id: UUID) {
    private val directory = File(files, DIRECTORY)
    private val record = File(directory, "$id.state")
    private val pending = File(directory, "$id.next")
    fun <T> locked(action: InitializationJournal.() -> T): T = synchronized(locks.getOrPut(record.canonicalPath) { Any() }) {
        if (!directory.exists()) {
            if (!directory.mkdirs()) throw java.io.IOException("Cannot initialize storage metadata")
            syncDirectory(directory.parentFile!!)
        }
        RandomAccessFile(File(directory, "$id.lock"), "rw").use { file ->
            file.channel.lock().use { action() }
        }
    }
    fun read(): InitializationState? {
        if (pending.exists()) throw StorageUnavailable(StorageFailure.INITIALIZATION_UNCERTAIN)
        if (!record.exists()) return null
        if (record.length() !in 1..160) throw StorageUnavailable(StorageFailure.INITIALIZATION_UNCERTAIN)
        return InitializationRecord.decode(id, record.readText(Charsets.UTF_8))
    }
    /** Called only while publishing a genuinely new UUID, BEFORE publishing its active pointer. */
    fun authorizeFresh() {
        if (record.exists() || pending.exists()) throw StorageUnavailable(StorageFailure.INITIALIZATION_UNCERTAIN)
        write(InitializationState.AUTHORIZED)
    }
    fun consumeCreation() {
        InitializationRecord.mayCreate(read())
        write(InitializationState.CREATING) // File + rename + directory sync complete before Room can create anything.
    }
    fun ready() {
        val state = read() // Never replace a corrupted or incomplete marker.
        if (state != InitializationState.READY) write(InitializationState.READY)
    }
    private fun write(state: InitializationState) {
        if (!pending.createNewFile()) throw StorageUnavailable(StorageFailure.INITIALIZATION_UNCERTAIN)
        // An interruption leaves .next and therefore blocks future opening, even if an older AUTHORIZED remains.
        FileOutputStream(pending).use { out ->
            out.write(InitializationRecord.encode(id, state).toByteArray(Charsets.UTF_8)); out.fd.sync()
        }
        Os.rename(pending.path, record.path)
        syncDirectory(directory)
    }
    private fun syncDirectory(file: File) {
        val fd = Os.open(file.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    companion object {
        const val DIRECTORY = "storage-initialization"
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
