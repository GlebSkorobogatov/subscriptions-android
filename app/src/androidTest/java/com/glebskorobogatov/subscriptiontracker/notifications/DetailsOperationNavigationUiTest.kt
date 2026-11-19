package com.glebskorobogatov.subscriptiontracker.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.glebskorobogatov.subscriptiontracker.app.DetailsOperationPause
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabase
import com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabaseFactory
import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState
import com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture
import com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Real DetailsViewModel and Room operations paused before their first write. */
class DetailsOperationNavigationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dataset = UUID.randomUUID()
    private val a = row("Подписка А")
    private val b = row("Подписка Б")

    @Before fun seed() {
        authorizeFreshFixture(context, dataset)
        database { it.subscriptions().insert(a); it.subscriptions().insert(b) }
    }

    @After fun cleanup() {
        releaseGate()
        val name = "subscriptions-$dataset.db"
        context.deleteDatabase(name)
        forgetFixtureInitialization(context, name)
    }

    @Test fun archiveWaitsForItsOwnExitBeforeOpeningTheOtherDetails() = openA { scenario ->
        val pause = submit("archive")
        notify(scenario, b.id, pause)
        pause.release.complete(Unit)
        awaitTag("details_${b.id}")
        assertEquals(SubscriptionState.ARCHIVED, database { it.subscriptions().find(a.id)?.state })
        assertReturnsHome(scenario)
    }

    @Test fun pendingNotificationSurvivesRecreationDuringDelete() = openA { scenario ->
        val pause = submit("delete")
        notify(scenario, b.id, pause)
        scenario.recreate()
        awaitTag("details_dialog")
        compose.onNodeWithTag("details_submit").assertIsNotEnabled()
        compose.onNodeWithTag("details_${b.id}").assertDoesNotExist()
        pause.release.complete(Unit)
        awaitTag("details_${b.id}")
        assertNull(database { it.subscriptions().find(a.id) })
        assertReturnsHome(scenario)
    }

    @Test fun notificationForTheSameArchiveShowsTheCurrentArchivedRecordOnce() = openA { scenario ->
        val pause = submit("archive")
        notify(scenario, a.id, pause)
        pause.release.complete(Unit)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("В архиве").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("details_${a.id}").assertCountEquals(1)
        assertReturnsHome(scenario)
    }

    @Test fun notificationForTheDeletedRecordFallsBackToHome() = openA { scenario ->
        val pause = submit("delete")
        notify(scenario, a.id, pause)
        pause.release.complete(Unit)
        awaitTag("subscriptions_screen")
        compose.onNodeWithTag("details_${a.id}").assertDoesNotExist()
        assertNull(database { it.subscriptions().find(a.id) })
    }

    @Test fun conflictedDeleteKeepsItsErrorAfterTheOtherDetailsAndReloadsWithoutDeleting() = openA { scenario ->
        val pause = submit("delete")
        database { it.subscriptions().update(a.copy(name = "Свежая версия")) }
        notify(scenario, b.id, pause)
        pause.release.complete(Unit)
        awaitTag("details_${b.id}")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("details_reload")
        compose.onNodeWithTag("details_submit").assertIsNotEnabled()
        compose.onNodeWithTag("details_reload").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Свежая версия").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("details_dialog").assertDoesNotExist()
        assertEquals("Свежая версия", database { it.subscriptions().find(a.id)?.name })
    }

    @Test fun lostDeleteResponseReloadsToNotFoundWithASafeReturn() = openA {
        val pause = submit("delete", loseResponse = true)
        pause.release.complete(Unit)
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("details_reload") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("details_reload").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Вернуться к подпискам").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("details_dialog").assertDoesNotExist()
        compose.onNodeWithText("Вернуться к подпискам").performClick()
        awaitTag("subscriptions_screen")
        assertNull(database { it.subscriptions().find(a.id) })
        assertEquals(b, database { it.subscriptions().find(b.id) })
    }

    @Test fun lockedFormRejectsBackgroundTouchesAndBackUntilReload() = openA {
        val back = compose.onNodeWithContentDescription("Назад").fetchSemanticsNode().boundsInWindow.center
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val pause = submit("disable", loseResponse = true)
        fun tryToDismiss() {
            device.click(back.x.toInt(), back.y.toInt())
            device.pressBack()
            compose.onNodeWithTag("details_dialog").assertIsDisplayed()
            compose.onNodeWithTag("details_${a.id}").assertExists()
            compose.onNodeWithText("Отмена").assertIsNotEnabled()
        }
        tryToDismiss()
        pause.release.complete(Unit)
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("details_reload") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        tryToDismiss()
        compose.onNodeWithTag("details_reload").performScrollTo().performClick()
        compose.onNodeWithTag("details_dialog").assertDoesNotExist()
        compose.onNodeWithText("Отменена").assertExists()
    }

    private fun openA(block: (ActivityScenario<IsolatedMainActivity>) -> Unit) {
        ActivityScenario.launch<IsolatedMainActivity>(intent()).whileAlive { scenario ->
            try {
                awaitTag("subscription_${a.id}")
                compose.onNodeWithTag("subscription_${a.id}").performClick()
                awaitTag("details_menu")
                block(scenario)
            } finally {
                releaseGate()
            }
        }
    }

    private fun submit(action: String, loseResponse: Boolean = false): DetailsOperationPause {
        compose.onNodeWithTag("details_menu").performClick()
        compose.onNodeWithTag("details_action_$action").performClick()
        val pause = DetailsOperationPause(dataset, loseResponse)
        DetailsOperationPause.current = pause
        compose.onNodeWithTag("details_submit").performClick()
        compose.waitUntil(15_000) { pause.entered.isCompleted }
        compose.onNodeWithTag("details_submit").assertIsNotEnabled()
        compose.onNodeWithTag("details_reload").assertIsNotEnabled()
        return pause
    }

    private fun notify(scenario: ActivityScenario<IsolatedMainActivity>, id: UUID, pause: DetailsOperationPause) {
        val incoming = intent().setAction(NotificationRoute.OPEN).setData(Uri.parse(NotificationRoute(dataset, id).identity))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        scenario.onActivity { it.startActivity(incoming) }
        scenario.followActivityIntent()
        compose.waitUntil(15_000) { pause.notificationRead.isCompleted }
        assertEquals(id, runBlocking { pause.notificationRead.await() })
        compose.onNodeWithTag("details_${a.id}").assertExists()
        compose.onNodeWithTag("details_submit").assertIsNotEnabled()
        compose.onNodeWithTag("details_${b.id}").assertDoesNotExist()
    }

    private fun assertReturnsHome(scenario: ActivityScenario<IsolatedMainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("subscriptions_screen")
        compose.onNodeWithTag("details_${a.id}").assertDoesNotExist()
        compose.onNodeWithTag("details_${b.id}").assertDoesNotExist()
    }

    private fun releaseGate() {
        DetailsOperationPause.current?.release?.complete(Unit)
        DetailsOperationPause.current = null
    }

    private fun awaitTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun intent() = Intent(context, IsolatedMainActivity::class.java).putExtra("test_dataset", dataset.toString())

    private fun <T> database(block: (TrackerDatabase) -> T): T = runBlocking(Dispatchers.IO) {
        val db = TrackerDatabaseFactory.open(context, dataset)
        try { block(db) } finally { db.close() }
    }

    private fun row(name: String): SubscriptionEntity {
        val date = LocalDate.now().plusDays(3)
        val now = Instant.EPOCH
        return SubscriptionEntity(UUID.randomUUID(), name, null, 39900, FrequencyUnit.MONTH, 1,
            SubscriptionState.ACTIVE, date.year, date.monthValue, date.dayOfMonth, null, null, null, null, null, null,
            now, now, null)
    }
}
