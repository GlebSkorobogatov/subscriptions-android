package com.glebskorobogatov.subscriptiontracker.background

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import com.glebskorobogatov.subscriptiontracker.data.reminders.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small explicit payload, never serialized entities or unbounded event arrays. */
object ReminderWorkInput {
    fun data(generation: UUID, subscription: UUID? = null, fingerprint: String? = null): Data =
        workDataOf("version" to ReminderScheduling.VERSION, "generation" to generation.toString(),
            "subscription" to subscription?.toString(), "fingerprint" to fingerprint).also {
            require(it.toByteArray().size <= Data.MAX_DATA_BYTES)
        }
    fun reconciliation(generation: UUID): Data = Data.Builder().putAll(data(generation))
        .putBoolean("reconcile", true).build()
    fun generation(data: Data): UUID {
        require(data.getInt("version", -1) == ReminderScheduling.VERSION)
        return uuid(requireNotNull(data.getString("generation")))
    }
    fun uuid(value: String) = UUID.fromString(value).also { require(it.toString() == value) }
}

internal suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitReminder(): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try { continuation.resume(get()) } catch (error: Exception) { continuation.resumeWithException(error) }
    }, java.util.concurrent.Executor { it.run() })
    // Do not cancel a durable enqueue because its caller stopped collecting.
}

class WorkManagerReminderScheduler(context: Context,
    private val activeGeneration: () -> UUID = { ActiveGeneration(context).current() },
    private val now: () -> Instant = Instant::now) : ReminderWorkScheduler {
    private val manager = WorkManager.getInstance(context.applicationContext)
    override suspend fun requestReconcile() = enqueueGate.withLock { requestLocked() }
    /** OS latency is monotonic; after wall-clock changes KEEP alone cannot refresh an old delay.
     * Persist the repair job first; its apply waits for this same enqueue gate. */
    suspend fun rescheduleAfterClockChange() = enqueueGate.withLock {
        requestLocked()
        manager.getWorkInfosByTag(ROOT).awaitReminder()
            .filter { !it.state.isFinished && it.state != WorkInfo.State.RUNNING && DELIVERY in it.tags }
            .forEach { manager.cancelWorkById(it.id).result.awaitReminder() }
    }
    private suspend fun requestLocked() {
        val work = manager.getWorkInfosForUniqueWork(RECONCILE).awaitReminder()
        if (work.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) {
            val request = OneTimeWorkRequestBuilder<ReminderWorker>().addTag(ROOT).addTag(RECONCILE)
                .setInputData(ReminderWorkInput.reconciliation(activeGeneration()))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            manager.beginUniqueWork(RECONCILE, ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue().result.awaitReminder()
        }
    }
    override suspend fun apply(generation: UUID, tasks: List<ScheduledReminder>, wake: Instant) {
        enqueueGate.withLock {
            // Recheck AFTER acquiring the global apply gate: a previously current runtime may
            // have waited here while another generation installed its requests.
            if (activeGeneration() != generation) return@withLock
            val wanted = tasks.associateBy { ReminderScheduling.workName(generation, it.subscription, it.fingerprint, it.at) }
            val wakeName = "reminder-wake:v1:$generation:$wake"
            for ((name, task) in wanted) {
                val request = OneTimeWorkRequestBuilder<ReminderWorker>().addTag(ROOT).addTag(DELIVERY).addTag(name)
                    .setInputData(ReminderWorkInput.data(generation, task.subscription, task.fingerprint))
                    .setInitialDelay(delay(task.at), TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
                val existing = manager.getWorkInfosForUniqueWork(name).awaitReminder()
                if (task.at > now() && existing.any { it.state == WorkInfo.State.RUNNING } &&
                    existing.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED })
                    manager.beginUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue().result.awaitReminder()
                else manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.awaitReminder()
            }
            val wakeRequest = OneTimeWorkRequestBuilder<ReminderWorker>().addTag(ROOT).addTag(WAKE).addTag(wakeName)
                .setInputData(ReminderWorkInput.data(generation))
                .setInitialDelay(delay(wake), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            manager.enqueueUniqueWork(wakeName, ExistingWorkPolicy.KEEP, wakeRequest).result.awaitReminder()
            val current = manager.getWorkInfosByTag(ROOT).awaitReminder()
            current.filter { !it.state.isFinished && RECONCILE !in it.tags }.forEach { info ->
                val keep = info.tags.any { it in wanted || it == wakeName }
                if (!keep && info.state != WorkInfo.State.RUNNING) manager.cancelWorkById(info.id).result.awaitReminder()
            }
        }
    }
    private fun delay(at: Instant) = Duration.between(now(), at).let { if (it.isNegative || it.isZero) 0 else Math.addExact(it.toMillis(), if (it.nano % 1_000_000 == 0) 0 else 1) }
    companion object {
        const val ROOT = "tracker-reminders-v1"
        const val RECONCILE = "tracker-reminders-reconcile-v1"
        const val DELIVERY = "tracker-reminders-delivery-v1"
        const val WAKE = "tracker-reminders-wake-v1"
        private val enqueueGate = Mutex()
    }
}

class ReminderWorker(context: Context, parameters: WorkerParameters,
    private val runtime: ReminderRuntime) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        require(inputData.getInt("version", -1) == ReminderScheduling.VERSION)
        if (inputData.getBoolean("reconcile", false)) runtime.reconcile(ReminderWorkInput.generation(inputData))
        else {
            val generation = ReminderWorkInput.generation(inputData)
            val subscription = inputData.getString("subscription")
            if (subscription == null) runtime.reconcile(generation)
            else {
                val fingerprint = requireNotNull(inputData.getString("fingerprint"))
                require(fingerprint.matches(Regex("[a-f0-9]{64}")))
                runtime.process(generation, ReminderWorkInput.uuid(subscription), fingerprint)
            }
        }
        Result.success()
    } catch (cancel: CancellationException) { throw cancel }
      catch (_: IllegalArgumentException) { Result.failure() }
      catch (error: Exception) {
          android.util.Log.w("ReminderWorker", "Background reminder attempt failed", error)
          if (runAttemptCount < 2) Result.retry() else Result.failure()
      }
}

class ReminderWorkerFactory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == ReminderWorker::class.java.name)
            ReminderWorker(appContext, workerParameters, ReminderRuntime(appContext, WorkManagerReminderScheduler(appContext), publisher = com.glebskorobogatov.subscriptiontracker.notifications.AndroidReminderPublisher(appContext))) else null
}

/** Lazy initialization: constructing Application never reads/creates a production dataset. */
class TrackerApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration get() = Configuration.Builder()
        .setWorkerFactory(ReminderWorkerFactory()).build()
}

class ReminderTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try { withTimeout(8_000) { WorkManagerReminderScheduler(context).rescheduleAfterClockChange() } }
            catch (error: Exception) { android.util.Log.w("ReminderTimeReceiver", "Reconciliation deferred to next wake/start", error) }
            finally { pending.finish(); scope.cancel() }
        }
    }
}
