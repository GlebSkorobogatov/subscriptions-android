package com.glebskorobogatov.subscriptiontracker.notifications

import android.content.*
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.*
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class NotificationNavigationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dataset: UUID
    private lateinit var sub: SubscriptionEntity
    @Before fun seed(): Unit = runBlocking(Dispatchers.IO) {
        dataset = UUID.randomUUID()
        val date = LocalDate.now().plusDays(3)
        sub = SubscriptionEntity(id = UUID.randomUUID(), name = "Целевая подписка", accountLabel = "Проверка UUID", priceMinor = 39900, intervalUnit = FrequencyUnit.MONTH, intervalCount = 1, state = SubscriptionState.ACTIVE, anchorYear = date.year, anchorMonth = date.monthValue, anchorDay = date.dayOfMonth, currentPeriodStartOverride = null, currentPeriodEnd = null, cancelledAccessEndExclusive = null, frozenCancelledPeriodStart = null, logoAssetId = null, catalogLogoId = null, createdAt = Instant.now(), updatedAt = Instant.now(), archivedAt = null)
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset); val db = TrackerDatabaseFactory.open(context,dataset)
        try { db.subscriptions().insert(sub) } finally { db.close() }
    }
    @After fun clean(): Unit {
        context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }
        java.io.File(context.filesDir,"datastore/settings-$dataset.preferences_pb").delete()
        context.deleteSharedPreferences("test-notification-offer-$dataset")
    }
    private fun intent(raw: String = NotificationRoute(dataset,sub.id).identity) = Intent(context,IsolatedMainActivity::class.java)
        .setAction(NotificationRoute.OPEN).setData(Uri.parse(raw)).putExtra("test_dataset",dataset.toString())
    private fun details() { compose.waitUntil(5_000) { compose.onAllNodesWithTag("details_${sub.id}").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("Изменить").fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithText("Изменить").assertExists() }
    private fun home() { compose.waitUntil(5_000) { compose.onAllNodesWithTag("subscriptions_screen").fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithTag("bottom_navigation").assertExists() }
    @Test fun coldActivityIntentOpensCorrectUuidAndBackReturnsHome() {
        ActivityScenario.launch<IsolatedMainActivity>(intent()).use { scenario ->
            details(); scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; home()
        }
    }
    @Test fun recreationDoesNotReplayConsumedNavigation() {
        ActivityScenario.launch<IsolatedMainActivity>(intent()).use { scenario ->
            details(); scenario.recreate(); details()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; home()
            scenario.recreate(); home(); compose.onNodeWithText("Изменить").assertDoesNotExist()
        }
    }
    @Test fun duplicateWarmIntentDoesNotAddAnotherDetailsDestination() {
        ActivityScenario.launch<IsolatedMainActivity>(intent()).use { scenario ->
            details(); scenario.onActivity { it.startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }; compose.waitForIdle()
            details(); scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; home()
        }
    }
    @Test fun actualImmutablePendingIntentStartsColdActivityAndBackWorks() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(IsolatedMainActivity::class.java.name,null,false)
        val pending = AndroidReminderPublisher(context).contentIntent(NotificationRoute(dataset,sub.id),
            Intent(context,IsolatedMainActivity::class.java).putExtra("test_dataset",dataset.toString()))
        var activity: android.app.Activity? = null
        try {
            pending.send()
            activity = instrumentation.waitForMonitorWithTimeout(monitor,5_000)
            assertNotNull("Actual PendingIntent must launch the isolated Activity",activity)
            details()
            instrumentation.runOnMainSync { (activity as IsolatedMainActivity).onBackPressedDispatcher.onBackPressed() }
            home()
        } finally {
            instrumentation.removeMonitor(monitor)
            activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
            pending.cancel()
        }
    }
    @Test fun malformedUuidHasSafeHomeFallback() {
        ActivityScenario.launch<IsolatedMainActivity>(intent("tracker-reminder://subscription/v1/$dataset/bad-id")).use { home() }
    }
    @Test fun oldGenerationHasSafeHomeFallback() {
        ActivityScenario.launch<IsolatedMainActivity>(intent(NotificationRoute(UUID.randomUUID(),sub.id).identity)).use { home() }
    }
    @Test fun deletedSubscriptionHasSafeHomeFallback() {
        runBlocking(Dispatchers.IO) { val db = TrackerDatabaseFactory.open(context,dataset); try { db.subscriptions().delete(sub.id) } finally { db.close() } }
        ActivityScenario.launch<IsolatedMainActivity>(intent()).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Добавить первую подписку").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Изменить").assertDoesNotExist()
        }
    }
    @Test fun archivedSubscriptionStillOpensItsExistingDetails() {
        runBlocking(Dispatchers.IO) { val db = TrackerDatabaseFactory.open(context,dataset); try { db.subscriptions().update(sub.copy(state=SubscriptionState.ARCHIVED)) } finally { db.close() } }
        ActivityScenario.launch<IsolatedMainActivity>(intent()).use {
            details()
            compose.onNodeWithTag("details_management").performScrollTo().performClick()
            compose.onNodeWithText("Возобновить подписку").performScrollTo().assertIsDisplayed()
        }
    }
    @Test fun unknownOldNotificationActionCannotMutateSubscriptionOnLaunchOrRecreation() {
        ActivityScenario.launch<IsolatedMainActivity>(intent().setAction("synthetic.retired.notification.action")).use { scenario ->
            home(); scenario.recreate(); home()
            runBlocking(Dispatchers.IO) {
                val db = TrackerDatabaseFactory.open(context,dataset)
                try {
                    assertEquals(sub,db.subscriptions().find(sub.id))
                    assertTrue(db.deliveries().all().isEmpty())
                } finally { db.close() }
            }
            compose.onNodeWithTag("settings_time_dialog").assertDoesNotExist()
        }
    }

}
