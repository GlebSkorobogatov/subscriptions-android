package com.glebskorobogatov.subscriptiontracker.notifications

import android.content.*
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.*
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import com.glebskorobogatov.subscriptiontracker.ui.performScrollToIfScrollable
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
        ActivityScenario.launch<IsolatedMainActivity>(intent()).whileAlive { scenario ->
            details(); scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; home()
        }
    }
    @Test fun recreationDoesNotReplayConsumedNavigation() {
        ActivityScenario.launch<IsolatedMainActivity>(intent()).whileAlive { scenario ->
            details(); scenario.recreate(); details()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; home()
            scenario.recreate(); home(); compose.onNodeWithText("Изменить").assertDoesNotExist()
        }
    }
    @Test fun duplicateWarmIntentDoesNotAddAnotherDetailsDestination() {
        ActivityScenario.launch<IsolatedMainActivity>(intent()).whileAlive { scenario ->
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
        ActivityScenario.launch<IsolatedMainActivity>(intent("tracker-reminder://subscription/v1/$dataset/bad-id")).whileAlive { home() }
    }
    @Test fun oldGenerationHasSafeHomeFallback() {
        ActivityScenario.launch<IsolatedMainActivity>(intent(NotificationRoute(UUID.randomUUID(),sub.id).identity)).whileAlive { home() }
    }
    @Test fun deletedSubscriptionHasSafeHomeFallback() {
        runBlocking(Dispatchers.IO) { val db = TrackerDatabaseFactory.open(context,dataset); try { db.subscriptions().delete(sub.id) } finally { db.close() } }
        ActivityScenario.launch<IsolatedMainActivity>(intent()).whileAlive {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Добавить подписку").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Изменить").assertDoesNotExist()
        }
    }
    @Test fun archivedSubscriptionStillOpensItsExistingDetails() {
        runBlocking(Dispatchers.IO) { val db = TrackerDatabaseFactory.open(context,dataset); try { db.subscriptions().update(sub.copy(state=SubscriptionState.ARCHIVED)) } finally { db.close() } }
        ActivityScenario.launch<IsolatedMainActivity>(intent()).whileAlive {
            details()
            compose.onNodeWithTag("details_management").performScrollTo().performClick()
            compose.onNodeWithText("Возобновить подписку").performScrollTo().assertIsDisplayed()
        }
    }
    @Test fun unknownOldNotificationActionCannotMutateSubscriptionOnLaunchOrRecreation() {
        ActivityScenario.launch<IsolatedMainActivity>(intent().setAction("synthetic.retired.notification.action")).whileAlive { scenario ->
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


    private fun draft(scenario: ActivityScenario<IsolatedMainActivity>) {
        home(); compose.onNodeWithContentDescription("Добавить подписку").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("field_name").performTextInput("Несохранённый учебный черновик")
        scenario.onActivity { it.startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        scenario.followActivityIntent()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Продолжить редактирование").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun cancellingAnOlderExitDoesNotDropANewerNotification() {
        val date = LocalDate.now().plusDays(4)
        val other = sub.copy(id = UUID.randomUUID(), name = "Вторая цель уведомления", accountLabel = null,
            anchorYear = date.year, anchorMonth = date.monthValue, anchorDay = date.dayOfMonth)
        runBlocking(Dispatchers.IO) {
            val db = TrackerDatabaseFactory.open(context, dataset)
            try { db.subscriptions().insert(other) } finally { db.close() }
        }
        ActivityScenario.launch<IsolatedMainActivity>(intent().setAction(Intent.ACTION_MAIN).setData(null)).whileAlive { scenario ->
            draft(scenario)
            scenario.onActivity {
                it.startActivity(intent(NotificationRoute(dataset, other.id).identity)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            scenario.followActivityIntent()
            compose.waitForIdle()
            compose.onNodeWithText("Продолжить редактирование").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Выйти без сохранения").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag("details_${other.id}").fetchSemanticsNodes().isNotEmpty()
            }
            if(compose.onAllNodesWithText("Выйти без сохранения").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("Выйти без сохранения").performClick()
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("details_${other.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("details_${sub.id}").assertDoesNotExist()
        }
    }

    @Test fun notificationDuringEditorRequiresDiscardAndKeepCancelsPendingNavigation() {
        ActivityScenario.launch<IsolatedMainActivity>(intent().setAction(Intent.ACTION_MAIN).setData(null)).whileAlive { scenario ->
            draft(scenario)
            compose.onNodeWithText("Продолжить редактирование").performClick()
            compose.onNodeWithTag("field_name").assertTextContains("Несохранённый учебный черновик")
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
            recreateForegroundActivity()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("field_name").assertTextContains("Несохранённый учебный черновик")
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithText("Выйти без сохранения").performClick()
            home(); compose.onNodeWithTag("details_${sub.id}").assertDoesNotExist()
        }
    }
    @Test fun confirmedDiscardContinuesNotificationToCorrectDetailsWithoutSavingDraft() {
        ActivityScenario.launch<IsolatedMainActivity>(intent().setAction(Intent.ACTION_MAIN).setData(null)).whileAlive { scenario ->
            draft(scenario)
            compose.onNodeWithText("Выйти без сохранения").performClick()
            details(); compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
            runBlocking(Dispatchers.IO) {
                val db = TrackerDatabaseFactory.open(context,dataset)
                try { assertEquals(listOf(sub),db.subscriptions().all()) } finally { db.close() }
            }
        }
    }
    @Test fun deferredNotificationRechecksRecordAfterDeletionBeforeDiscard() {
        ActivityScenario.launch<IsolatedMainActivity>(intent().setAction(Intent.ACTION_MAIN).setData(null)).whileAlive { scenario ->
            draft(scenario)
            runBlocking(Dispatchers.IO) {
                val db = TrackerDatabaseFactory.open(context,dataset)
                try { db.subscriptions().delete(sub.id) } finally { db.close() }
            }
            compose.onNodeWithText("Выйти без сохранения").performClick()
            home(); compose.onNodeWithTag("details_${sub.id}").assertDoesNotExist()
        }
    }
    @Test fun recreationDuringExitConfirmationKeepsThePendingNavigation() {
        ActivityScenario.launch<IsolatedMainActivity>(intent().setAction(Intent.ACTION_MAIN).setData(null)).whileAlive { scenario ->
            draft(scenario)
            recreateForegroundActivity()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Выйти без сохранения").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("field_name").assertTextContains("Несохранённый учебный черновик")
            compose.onNodeWithText("Выйти без сохранения").performClick()
            details()
            runBlocking(Dispatchers.IO) {
                val db = TrackerDatabaseFactory.open(context,dataset)
                try { assertEquals(listOf(sub),db.subscriptions().all()) } finally { db.close() }
            }
        }
    }
    @Test fun notificationDuringSaveKeepsTheRecordAndStillOpensTheRequest() {
        ActivityScenario.launch<IsolatedMainActivity>(intent().setAction(Intent.ACTION_MAIN).setData(null)).whileAlive { scenario ->
            home(); compose.onNodeWithContentDescription("Добавить подписку").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("field_name").performTextInput("Сохранённая учебная")
            compose.onNodeWithTag("field_amount").performTextInput("150")
            compose.onNodeWithText("Сохранить").performScrollToIfScrollable().performClick()
            scenario.onActivity { it.startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            scenario.followActivityIntent()
            details()
            runBlocking(Dispatchers.IO) {
                val db = TrackerDatabaseFactory.open(context,dataset)
                try { assertEquals(setOf(sub.name,"Сохранённая учебная"),db.subscriptions().all().map { it.name }.toSet()) }
                finally { db.close() }
            }
        }
    }

    @Test fun notificationWithOpenCalendarAsksBeforeLeavingAndCancelKeepsTheDraft() {
        ActivityScenario.launch<IsolatedMainActivity>(intent().setAction(Intent.ACTION_MAIN).setData(null)).whileAlive { scenario ->
            openEditor()
            compose.onNodeWithTag("field_name").performTextInput("Черновик над календарём")
            compose.onNodeWithTag("date_picker_date").performScrollTo().performClick()
            compose.onNodeWithTag("editor_date_sheet").assertIsDisplayed()
            deliver(scenario)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Продолжить редактирование").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("editor_date_sheet").assertDoesNotExist()
            compose.onNodeWithText("Продолжить редактирование").performClick()
            compose.onNodeWithTag("field_name").assertTextContains("Черновик над календарём")
            compose.onNodeWithTag("details_${sub.id}").assertDoesNotExist()
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        }
    }

    @Test fun notificationWithOpenCatalogContinuesToDetailsAfterDiscard() {
        ActivityScenario.launch<IsolatedMainActivity>(intent().setAction(Intent.ACTION_MAIN).setData(null)).whileAlive { scenario ->
            openEditor()
            compose.onNodeWithTag("field_name").performTextInput("Черновик над каталогом")
            compose.onNodeWithTag("editor_choose_catalog").performClick()
            compose.onNodeWithTag("catalog_sheet").assertIsDisplayed()
            deliver(scenario)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Выйти без сохранения").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("catalog_sheet").assertDoesNotExist()
            compose.onNodeWithText("Выйти без сохранения").performClick()
            details()
            runBlocking(Dispatchers.IO) {
                val db = TrackerDatabaseFactory.open(context, dataset)
                try { assertEquals(listOf(sub), db.subscriptions().all()) } finally { db.close() }
            }
        }
    }

    @Test fun cleanEditorWithOpenCalendarFollowsTheNotificationWithoutConfirmation() {
        ActivityScenario.launch<IsolatedMainActivity>(intent().setAction(Intent.ACTION_MAIN).setData(null)).whileAlive { scenario ->
            openEditor()
            compose.onNodeWithTag("date_picker_date").performScrollTo().performClick()
            compose.onNodeWithTag("editor_date_sheet").assertIsDisplayed()
            deliver(scenario)
            details()
            compose.onNodeWithText("Выйти без сохранения").assertDoesNotExist()
        }
    }

    @Test fun openCatalogNotificationSurvivesRecreationAndDeletedTargetReturnsHome() {
        ActivityScenario.launch<IsolatedMainActivity>(intent().setAction(Intent.ACTION_MAIN).setData(null)).whileAlive { scenario ->
            openEditor()
            compose.onNodeWithTag("field_name").performTextInput("Черновик перед удалением")
            compose.onNodeWithTag("editor_choose_catalog").performClick()
            compose.onNodeWithTag("catalog_sheet").assertIsDisplayed()
            deliver(scenario)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Выйти без сохранения").fetchSemanticsNodes().isNotEmpty() }
            recreateForegroundActivity()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Выйти без сохранения").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("field_name").assertTextContains("Черновик перед удалением")
            runBlocking(Dispatchers.IO) {
                val db = TrackerDatabaseFactory.open(context, dataset)
                try { db.subscriptions().delete(sub.id) } finally { db.close() }
            }
            compose.onNodeWithText("Выйти без сохранения").performClick()
            home()
            compose.onNodeWithTag("details_${sub.id}").assertDoesNotExist()
        }
    }

    private fun openEditor() {
        home()
        compose.onNodeWithContentDescription("Добавить подписку").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun deliver(scenario: ActivityScenario<IsolatedMainActivity>) {
        scenario.onActivity { it.startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        scenario.followActivityIntent()
    }
}
