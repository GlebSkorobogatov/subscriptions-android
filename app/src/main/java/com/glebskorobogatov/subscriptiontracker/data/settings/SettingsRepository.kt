package com.glebskorobogatov.subscriptiontracker.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}

internal object SettingsCodec {
    private val theme = stringPreferencesKey("theme")
    private val payment = stringSetPreferencesKey("payment_offsets")
    private val animations = booleanPreferencesKey("animations_enabled")
    private val costRankingPeriod = stringPreferencesKey("cost_ranking_period")
    private val version = intPreferencesKey("settings_version")
    fun read(p: Preferences) = Settings(
        theme = p[theme]?.let { raw -> Theme.entries.firstOrNull { it.name == raw } } ?: Theme.SYSTEM,
        // Missing means defaults; a present empty set remains disabled. Invalid legacy members
        // are discarded, never replaced with newly enabled reminder days. I/O errors propagate.
        renewalOffsets = p[payment]?.mapNotNull(String::toIntOrNull)
            ?.filter { it in setOf(7, 3, 1, 0) }?.toSet() ?: setOf(3, 1),
        animationsEnabled = p[animations] ?: true,
        version = p[version] ?: 1,
        costRankingPeriod = p[costRankingPeriod]?.let { raw ->
            CostRankingPeriod.entries.firstOrNull { it.name == raw }
        } ?: CostRankingPeriod.MONTH,
    )
    fun write(p: MutablePreferences, value: Settings) {
        p[theme] = value.theme.name
        p[payment] = value.renewalOffsets.map(Int::toString).toSet()
        p[animations] = value.animationsEnabled
        p[version] = value.version
        p[costRankingPeriod] = value.costRankingPeriod.name
        // Retired and unknown keys are preserved as inert legacy data, never read or executed.
    }
}

/**
 * DataStore 1.1.7 can drop a write from a collector whose first read already captured that version.
 * After a commit, every collector starts a new read. The store stays the only source: a missed
 * confirmation cannot suppress a later value, and a read failure is not replaced by a remembered one.
 */
internal class PreferencesSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    private val generation = MutableStateFlow(0)
    private val writes = Mutex()
    override val settings: Flow<Settings> =
        generation.flatMapLatest { store.data.map(SettingsCodec::read) }.distinctUntilChanged()
    override suspend fun update(transform: (Settings) -> Settings) {
        writes.withLock {
            try {
                store.edit { preferences ->
                    SettingsCodec.write(preferences, transform(SettingsCodec.read(preferences)))
                }
            } finally {
                withContext(NonCancellable) { generation.value = generation.value + 1 }
            }
        }
    }
}

/** Process-wide owner. Acquire a lease per consumer; suspend close waits for final file release. */
object DatasetSettingsFactory {
    private data class Entry(val scope: CoroutineScope, val repository: SettingsRepository, var references: Int)
    private val entries = mutableMapOf<String, Entry>()
    private val mutex = kotlinx.coroutines.sync.Mutex()
    suspend fun acquire(context: Context, datasetId: UUID): Lease {
        mutex.lock()
        try {
            val file = context.applicationContext.preferencesDataStoreFile("settings-$datasetId")
            val key = file.canonicalPath
            val entry = entries[key] ?: run {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
                Entry(scope, PreferencesSettingsRepository(store), 0).also { entries[key] = it }
            }
            entry.references++
            return Lease(entry.repository) {
                mutex.lock()
                try {
                    entry.references--
                    if (entry.references == 0) {
                        entry.scope.coroutineContext.job.cancelAndJoin()
                        entries.remove(key)
                    }
                } finally { mutex.unlock() }
            }
        } finally { mutex.unlock() }
    }
    class Lease internal constructor(val repository: SettingsRepository, private val release: suspend () -> Unit) {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        suspend fun close() = withContext(NonCancellable) {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}
