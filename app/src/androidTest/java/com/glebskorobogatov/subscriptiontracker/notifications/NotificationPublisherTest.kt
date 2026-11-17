package com.glebskorobogatov.subscriptiontracker.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.test.uiautomator.*
import java.util.regex.Pattern
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.glebskorobogatov.subscriptiontracker.background.*
import com.glebskorobogatov.subscriptiontracker.data.reminders.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderRules
import com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderMessage
import com.glebskorobogatov.subscriptiontracker.domain.reminders.PlannedReminder
import com.glebskorobogatov.subscriptiontracker.domain.reminders.CurrentReminderReason
import java.time.*
import java.util.UUID
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Uses the real NotificationManager, Room and F-02 runtime, never a fake publisher. */
class NotificationPublisherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val publisher = AndroidReminderPublisher(context)
    private lateinit var dataset: UUID
    private lateinit var db: TrackerDatabase
    private lateinit var lease: DatasetSettingsFactory.Lease
    private lateinit var generation: ReminderInfrastructureTest.TestGeneration
    private lateinit var scheduler: ReminderInfrastructureTest.RecordingScheduler
    private lateinit var sub: SubscriptionEntity
    private var instant = Instant.parse("2027-01-01T00:00:00Z")
    @Before fun open(): Unit = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        dataset = UUID.randomUUID(); generation = ReminderInfrastructureTest.TestGeneration(dataset)
        scheduler = ReminderInfrastructureTest.RecordingScheduler()
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset); db = TrackerDatabaseFactory.open(context,dataset); lease = DatasetSettingsFactory.acquire(context,dataset)
        sub = SubscriptionEntity(id = UUID.randomUUID(), name = "Одинаковый сервис", accountLabel = "Личный", priceMinor = 39900, intervalUnit = FrequencyUnit.MONTH, intervalCount = 1, state = SubscriptionState.ACTIVE, anchorYear = 2027, anchorMonth = 1, anchorDay = 5, currentPeriodStartOverride = null, currentPeriodEnd = null, cancelledAccessEndExclusive = null, frozenCancelledPeriodStart = null, logoAssetId = null, catalogLogoId = null, createdAt = instant, updatedAt = instant, archivedAt = null)
        withContext(Dispatchers.IO) { db.subscriptions().insert(sub) }
        publisher.createChannels()
    }
    @After fun close(): Unit = runBlocking {
        manager.activeNotifications.filter { it.tag?.contains(dataset.toString()) == true }.forEach { manager.cancel(it.tag,it.id) }
        withContext(Dispatchers.IO) { db.close() }; lease.close()
        context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }; File(context.filesDir,"datastore/settings-$dataset.preferences_pb").delete()
    }
    private fun runtime(hook: suspend () -> Unit = {}) = ReminderRuntime(context,scheduler,generation,publisher,
        { instant }, { ZoneOffset.UTC },hook)
    private suspend fun publish(): PublicationResult? {
        val run = runtime(); run.reconcile(); val task = scheduler.tasks.single { it.subscription == sub.id }
        instant = Instant.parse("2027-01-04T10:00:00Z")
        return run.process(dataset, sub.id, task.fingerprint)
    }
    private fun child(id: UUID = sub.id) = manager.activeNotifications.singleOrNull { it.tag == ReminderScheduling.identity(dataset,id) }
    private suspend fun await(label: String = "Expected OS notification state", predicate: () -> Boolean) {
        try { withTimeout(5_000) { while (!predicate()) delay(25) } }
        catch (e: TimeoutCancellationException) { throw AssertionError("$label: " + manager.activeNotifications.map { it.tag to it.notification.flags },e) }
    }
    @Test fun realNotificationUsesFreshTextAndAck(): Unit = runBlocking {
        assertEquals(PublicationResult.PUBLISHED,publish()); await { child() != null }
        assertTrue(child()!!.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Завтра ожидается платёж 399,00 RUB"))
        assertTrue(db.deliveries().all().all { it.state == DeliveryState.DELIVERED })
        assertEquals(Notification.VISIBILITY_PRIVATE,child()!!.notification.visibility)
        assertNotNull(child()!!.notification.publicVersion)
    }
    @Test fun russianPluralsDoNotDependOnDeviceLanguage(): Unit {
        assertEquals("1 день",AndroidReminderWords(context).days(1))
        assertEquals("2 дня",AndroidReminderWords(context).days(2))
        assertEquals("5 дней",AndroidReminderWords(context).days(5))
        assertEquals("21 день",AndroidReminderWords(context).days(21))
    }
    @Test fun stableChannelsHaveStandardImportance(): Unit = runBlocking {
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT,manager.getNotificationChannel(AndroidReminderPublisher.PAYMENTS).importance)
        assertEquals("Продления",manager.getNotificationChannel(AndroidReminderPublisher.PAYMENTS).name.toString())
        publisher.createChannels(); assertNotNull(manager.getNotificationChannel(AndroidReminderPublisher.PAYMENTS))
    }
    @Test fun replayUpdatesSameSystemIdentity(): Unit = runBlocking {
        publish(); await { child() != null }
        val detail = ReminderPresentation(sub.name,Money(sub.priceMinor,CurrencyCode.of("RUB")))
        val input = com.glebskorobogatov.subscriptiontracker.data.integration.reminderInputs(listOf(sub),Settings())
        val events = ReminderRules().plan(input,LocalTime.of(9,0),Instant.parse("2027-01-01T00:00:00Z"),ZoneOffset.UTC)
        val msg = ReminderRules().dueMessages(input,LocalTime.of(9,0),events,instant,ZoneOffset.UTC).single()
        repeat(2) { assertEquals(PublicationResult.PUBLISHED,publisher.publish(NotificationIdentity(ReminderScheduling.identity(dataset,sub.id)),msg,detail)) }
        await { child() != null }; assertEquals(1,manager.activeNotifications.count { it.tag == ReminderScheduling.identity(dataset,sub.id) })
    }
    @Test fun legacyCancellationLeadStillUsesOnlyRenewalChannelAndCopy() = runBlocking {
        db.subscriptions().update(sub.copy()); publish(); await { child() != null }
        val notification = child()!!.notification
        assertEquals(AndroidReminderPublisher.PAYMENTS,notification.channelId)
        assertFalse(notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().contains("крайний срок"))
        assertTrue(db.deliveries().all().all { it.eventType == ReminderEventType.RENEWAL })
    }

    @Test fun independentChildrenAndStableSummaryAreRemovedWhenDismissed(): Unit = runBlocking {
        val second = sub.copy(id = UUID.randomUUID(),accountLabel = "Семейный")
        db.subscriptions().insert(second)
        val run = runtime(); run.reconcile(); val tasks = scheduler.tasks.toList()
        instant = Instant.parse("2027-01-04T10:00:00Z")
        tasks.forEach { run.process(dataset,it.subscription,it.fingerprint) }
        await("Two real children and summary") { child() != null && child(second.id) != null && manager.activeNotifications.any { it.tag == AndroidReminderPublisher.summaryTag(dataset) } }
        assertEquals(AndroidReminderPublisher.group(dataset),child()!!.notification.group)
        child()!!.notification.deleteIntent.send()
        await("Dismissing one child preserves the other") { child() == null && child(second.id) != null }
        child(second.id)!!.notification.deleteIntent.send()
        await("Last child removes summary") { manager.activeNotifications.none { it.tag?.contains(dataset.toString()) == true } }
    }
    @Test fun dismissingSummaryDoesNotLeaveOrResurrectChildren(): Unit = runBlocking {
        val second = sub.copy(id = UUID.randomUUID(),accountLabel = "Семейный")
        db.subscriptions().insert(second)
        val run = runtime(); run.reconcile(); val tasks = scheduler.tasks.toList()
        instant = Instant.parse("2027-01-04T10:00:00Z")
        tasks.forEach { run.process(dataset,it.subscription,it.fingerprint) }
        await("Summary is actually posted") { manager.activeNotifications.any { it.tag == AndroidReminderPublisher.summaryTag(dataset) } }
        manager.activeNotifications.single { it.tag == AndroidReminderPublisher.summaryTag(dataset) }.notification.deleteIntent.send()
        await("Summary dismissal removes its own children") { manager.activeNotifications.none { it.tag?.contains(dataset.toString()) == true } }
        run.reconcile()
        assertTrue(manager.activeNotifications.none { it.tag?.contains(dataset.toString()) == true })
    }
    @Test fun archiveCancelsAlreadyPostedNotification(): Unit = runBlocking {
        publish(); await { child() != null }; db.subscriptions().update(sub.copy(state = SubscriptionState.ARCHIVED))
        runtime().reconcile(); await { child() == null }
    }
    @Test fun deletionDoesNotNeedLedgerRowToCancelNotification(): Unit = runBlocking {
        publish(); await { child() != null }; db.subscriptions().delete(sub.id)
        runtime().reconcile(); await { child() == null }; assertTrue(db.deliveries().all().isEmpty())
    }
    @Test fun disabledRenewalCancelsNotification(): Unit = runBlocking {
        publish(); await { child() != null }; db.subscriptions().update(sub.copy(state = SubscriptionState.CANCELLED))
        runtime().reconcile(); await { child() == null }
    }
    @Test fun movedDateCancelsNotification(): Unit = runBlocking {
        publish(); await { child() != null }; db.subscriptions().update(sub.copy(anchorDay = 9))
        runtime().reconcile(); await { child() == null }
    }
    @Test fun changingInertLegacyDeadlineDoesNotCancelCurrentRenewal() = runBlocking {
        db.subscriptions().update(sub.copy()); publish(); await { child() != null }
        val original = child()!!.notification.extras.getString("tracker.reminder.post")
        db.subscriptions().update(sub.copy()); runtime().reconcile()
        assertNotNull(child()); assertEquals(original,child()!!.notification.extras.getString("tracker.reminder.post"))
    }

    @Test fun currentGenerationCleanupCancelsForeignPostedNotification(): Unit = runBlocking {
        publish(); await { child() != null }
        publisher.reconcilePublished(PublishedReminderSnapshot(UUID.randomUUID(),emptyList(),emptyMap(),LocalTime.of(9,0),instant,ZoneOffset.UTC))
        await { child() == null }
    }
    @Test fun generationChangesImmediatelyBeforeApiCannotPublish(): Unit = runBlocking {
        val run = runtime { generation.id = UUID.randomUUID() }; run.reconcile(); val task = scheduler.tasks.single()
        instant = Instant.parse("2027-01-04T10:00:00Z")
        assertNull(run.process(dataset,sub.id,task.fingerprint)); assertNull(child())
        assertTrue(db.deliveries().all().none { it.state == DeliveryState.DELIVERED })
    }
    @Test fun repeatedWorkerDoesNotPostAnotherLogicalMessage(): Unit = runBlocking {
        val run = runtime(); run.reconcile(); val task = scheduler.tasks.single()
        instant = Instant.parse("2027-01-04T10:00:00Z")
        run.process(dataset,sub.id,task.fingerprint); run.process(dataset,sub.id,task.fingerprint)
        await { child() != null }; assertEquals(1,manager.activeNotifications.count { it.tag == ReminderScheduling.identity(dataset,sub.id) })
    }
    @Test fun pendingIntentsUseImmutableDistinctUriIdentity(): Unit = runBlocking {
        val first = publisher.contentIntent(NotificationRoute(dataset,sub.id))
        assertTrue(first.isImmutable)
        assertEquals(first,publisher.contentIntent(NotificationRoute(dataset,sub.id)))
        assertNotEquals(first,publisher.contentIntent(NotificationRoute(dataset,UUID.randomUUID())))
        assertNotEquals(first,publisher.contentIntent(NotificationRoute(UUID.randomUUID(),sub.id)))
    }
    @Test fun systemChannelBlockIsRespectedWithoutFallbackOrFalseDelivery(): Unit = runBlocking {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE,context.packageName)
            .putExtra(AndroidSettings.EXTRA_CHANNEL_ID,AndroidReminderPublisher.PAYMENTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val selector = By.res(Pattern.compile("(android|com.android.settings):id/switch_widget"))
        try {
            val toggle = device.wait(Until.findObject(selector),5_000)
            assertNotNull("Real Android channel control",toggle); toggle.click()
            await { !publisher.access().renewalsEnabled }
            assertEquals(PublicationResult.UNAVAILABLE,publish()); assertNull(child())
            assertTrue(db.deliveries().all().all { it.state == DeliveryState.PENDING && it.deliveredAt == null })
            publisher.createChannels(); assertFalse(publisher.access().renewalsEnabled)
        } finally {
            if (!publisher.access().renewalsEnabled) device.wait(Until.findObject(selector),5_000)?.click()
            device.pressBack(); await { publisher.access().renewalsEnabled }
        }
    }
    @Test fun permissionOfferHistorySurvivesStoreReopening(): Unit = runBlocking {
        val name = "test-notification-offer-$dataset"
        try {
            val a = AndroidNotificationEnvironment(context,{ dataset },{},name)
            assertTrue(withContext(Dispatchers.IO) { a.claimAutomaticOffer() })
            val b = AndroidNotificationEnvironment(context,{ dataset },{},name)
            assertFalse(withContext(Dispatchers.IO) { b.claimAutomaticOffer() })
        } finally { context.deleteSharedPreferences(name) }
    }
    @android.annotation.SuppressLint("MissingPermission") // @Before grants permission for this synthetic notification fixture.
    @Test fun preUpdateCancellationNotificationIsRemovedWithoutAcknowledgingDelivery() = runBlocking {
        runtime().reconcile(); val before = db.deliveries().all()
        instant = Instant.parse("2027-01-04T10:00:00Z")
        val legacyChannel = "subscription_cancellations_v1"
        manager.createNotificationChannel(android.app.NotificationChannel(legacyChannel,"Synthetic legacy",NotificationManager.IMPORTANCE_DEFAULT))
        val extras = android.os.Bundle().apply {
            putString("tracker.reminder.route",NotificationRoute(dataset,sub.id).identity)
            putString("tracker.reminder.day","2027-01-04")
            putString("tracker.reminder.presentation","${sub.name}|${sub.priceMinor}|${CurrencyCode.of("RUB").value}")
            putStringArray("tracker.reminder.events",arrayOf("CANCELLATION|2027-01-05|1|2027-01-04T09:00"))
        }
        val notification = androidx.core.app.NotificationCompat.Builder(context,legacyChannel)
            .setSmallIcon(com.glebskorobogatov.subscriptiontracker.R.drawable.ic_notification)
            .setContentTitle("Synthetic retired reminder").addExtras(extras).build()
        manager.notify(ReminderScheduling.identity(dataset,sub.id),0,notification)
        await { child() != null }; runtime().reconcile(); await { child() == null }
        assertEquals(before.toSet(),db.deliveries().all().toSet())
        assertTrue(db.deliveries().all().none { it.state == DeliveryState.DELIVERED })
        assertEquals(sub,db.subscriptions().find(sub.id))
    }


    @Test fun fifteenRecoveredKeysRemainInNativeMetadataAndReconciliationAcceptsThem() = runBlocking {
        db.subscriptions().update(sub.copy(intervalUnit = FrequencyUnit.DAY,anchorDay = 1))
        lease.repository.update { it.copy(renewalOffsets = setOf(7,3,1,0)) }
        val run = runtime()
        for (day in 1..7) {
            instant = LocalDate.of(2027,1,day).atStartOfDay().toInstant(ZoneOffset.UTC)
            run.reconcile()
        }
        instant = Instant.parse("2027-01-08T09:00:00Z"); run.reconcile()
        val task = scheduler.tasks.single { it.subscription == sub.id }
        assertEquals(PublicationResult.PUBLISHED,run.process(dataset,sub.id,task.fingerprint))
        await { child() != null }
        val metadata = child()!!.notification.extras.getStringArray("tracker.reminder.events")!!
        assertEquals(15,metadata.size); assertEquals(15,metadata.toSet().size)
        assertEquals(8,child()!!.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().lines().size)
        assertEquals(15,db.deliveries().all().count { it.state == DeliveryState.DELIVERED })
        run.reconcile(); await { child() != null }
        publisher.dismiss(NotificationRoute(dataset,sub.id)); await { child() == null }
        run.reconcile(); assertNull(child()) // No new basis: handled keys cannot resurrect dismissal.
    }
    @Test fun oversizedOccurrenceMetadataIsExplicitlyRejectedRatherThanTruncated() = runBlocking {
        val id = com.glebskorobogatov.subscriptiontracker.domain.model.SubscriptionId(sub.id)
        val today = LocalDate.of(2027,1,1)
        val events = (0L..15L).map { n -> PlannedReminder(id,
            com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderEventType.RENEWAL,
            today.plusDays(n),0,today.plusDays(n).atTime(9,0)) }
        val message = ReminderMessage(id,today,events.map {
            CurrentReminderReason(it.eventType,it.targetDate,java.time.temporal.ChronoUnit.DAYS.between(today,it.targetDate),listOf(it)) })
        assertEquals(PublicationResult.PERMANENT_SKIP,publisher.publish(NotificationIdentity(ReminderScheduling.identity(dataset,sub.id)),
            message,ReminderPresentation(sub.name,Money(sub.priceMinor,CurrencyCode.of("RUB")))))
        assertNull(child()); assertTrue(db.deliveries().all().isEmpty())
    }
}
