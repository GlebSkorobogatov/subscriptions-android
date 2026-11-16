package com.glebskorobogatov.subscriptiontracker.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.app.NotificationManager
import androidx.work.WorkManager
import com.glebskorobogatov.subscriptiontracker.background.*
import com.glebskorobogatov.subscriptiontracker.data.reminders.*
import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderRules
import java.io.File
import java.time.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only, shell/DUMP protected, opt-in sandbox ONLY. Never available in the release APK.
 * Fixture models a saved warning whose execution was delayed; production rules/Worker are unchanged. */
class ReminderSystemProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.packageName != "com.glebskorobogatov.subscriptiontracker.f04test" ||
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val result = withTimeout(20_000) { execute(context.applicationContext,intent.getStringExtra("command")) }
                pending.setResult(0,result.toString(),null)
            } catch (error: Exception) {
                pending.setResult(1,JSONObject().put("error",error.javaClass.simpleName).put("detail",error.message).toString(),null)
            } finally { pending.finish(); scope.cancel() }
        }
    }
    private suspend fun execute(context: Context, command: String?): JSONObject {
        val generation = ActiveDataset(context.filesDir).getOrCreate()
        val manager = WorkManager.getInstance(context)
        val scheduler = WorkManagerReminderScheduler(context)
        val file = File(context.filesDir,"f04-system-fixture.json")
        if (command == "kick") {
            scheduler.requestReconcile()
            return JSONObject().put("requested",true)
        }
        require(command == "prepare" || command == "inspect")
        val db = TrackerDatabaseFactory.open(context,generation)
        var lease: DatasetSettingsFactory.Lease? = null
        try {
            val settingsLease = DatasetSettingsFactory.acquire(context,generation)
            lease = settingsLease
            if (command == "prepare") {
                // CI reuses only this freshly installed disposable package. No production datasets.
                check(db.subscriptions().all().isEmpty()) { "Fixture requires an empty isolated app" }
                val now = Instant.now(); val zone = ZoneId.systemDefault()
                val planned = now.atZone(zone).toLocalDate().minusDays(1).atTime(9,0)
                val target = planned.toLocalDate().plusDays(1)
                val subscription = UUID.randomUUID()
                val row = SubscriptionEntity(subscription,"F04 Test Service","Only fake data",39900,CurrencyCode.of("RUB"),
                    FrequencyUnit.MONTH,1,RenewalMode.MANUAL,LifecycleState.ACTIVE,target.year,target.monthValue,target.dayOfMonth,
                    0,null,null,null,null,0,null,null,now,now)
                db.subscriptions().insert(row)
                settingsLease.repository.update { it.copy(paymentOffsetsDefault = setOf(1)) }
                val settings = settingsLease.repository.settings.first()
                val ledger = ReminderLedger(db,generation)
                val earlier = ReminderRules().resolveLocal(planned,zone).toInstant().minusSeconds(60)
                val records = ledger.reconcile(settings,earlier,zone)
                check(records.size == 1)
                val fingerprint = ReminderScheduling.fingerprint(ledger.events(records),zone)
                // Persist a genuinely delayed WorkRequest; host recovery explicitly reconciles it.
                scheduler.apply(generation,listOf(ScheduledReminder(subscription,fingerprint,now.plusSeconds(86400))),now.plusSeconds(172800))
                val works = manager.getWorkInfosByTag(WorkManagerReminderScheduler.DELIVERY).get(10,TimeUnit.SECONDS)
                    .filter { !it.state.isFinished }
                check(works.size == 1)
                file.writeText(JSONObject().put("generation",generation.toString()).put("subscription",subscription.toString())
                    .put("work",works.single().id.toString()).put("target",target.toString()).toString())
            }
            val fixture = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            val works = manager.getWorkInfosByTag(WorkManagerReminderScheduler.ROOT).get(10,TimeUnit.SECONDS)
            return JSONObject().put("fixture",fixture).put("pid",android.os.Process.myPid()).put("zone",ZoneId.systemDefault().id)
                .put("time",settingsLease.repository.settings.first().reminderLocalTime.toString())
                .put("currentTarget",db.subscriptions().all().singleOrNull()?.let {
                    com.glebskorobogatov.subscriptiontracker.domain.calendar.DefaultRenewalCalendar().nextScheduledDate(it.toDomain().schedule).toString() })
                .put("rows",JSONArray(db.deliveries().all().map { JSONObject().put("id",it.id.toString())
                    .put("state",it.state.name).put("type",it.eventType.name).put("target",it.eventDate.toString())
                    .put("offset",it.offsetDays).put("claim",it.claimToken?.toString()) }))
                .put("work",JSONArray(works.map { JSONObject().put("id",it.id.toString()).put("state",it.state.name)
                    .put("delivery",WorkManagerReminderScheduler.DELIVERY in it.tags).put("control",WorkManagerReminderScheduler.RECONCILE in it.tags) }))
                .put("payments",db.subscriptions().all().sumOf { db.payments().forSubscription(it.id).size })
                .put("notifications",JSONArray(context.getSystemService(NotificationManager::class.java).activeNotifications.map {
                    JSONObject().put("tag",it.tag).put("id",it.id).put("title",it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
                        .put("text",it.notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)) }))
        } finally { withContext(NonCancellable) { try { db.close() } finally { lease?.close() } } }
    }
}
