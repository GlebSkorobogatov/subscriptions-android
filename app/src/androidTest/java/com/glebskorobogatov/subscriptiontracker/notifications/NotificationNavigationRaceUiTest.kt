package com.glebskorobogatov.subscriptiontracker.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import com.glebskorobogatov.subscriptiontracker.app.NavigationLookupPause
import com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity
import com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabaseFactory
import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Real Compose → ViewModel → Room → Navigation path.
 * The UI existence read is held until the test releases it, so a premature consumed() cannot win the race.
 */
class NotificationNavigationRaceUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dataset: UUID
    private lateinit var first: SubscriptionEntity
    private lateinit var second: SubscriptionEntity
    private var gate: CompletableDeferred<Unit>? = null

    @Before fun seed(): Unit = runBlocking(Dispatchers.IO) {
        dataset = UUID.randomUUID()
        val date = LocalDate.now().plusDays(3)
        first = row("Первая цель", date)
        second = row("Вторая цель", date.plusDays(1))
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset)
        val db = TrackerDatabaseFactory.open(context, dataset)
        try {
            db.subscriptions().insert(first)
            db.subscriptions().insert(second)
        } finally { db.close() }
    }

    @After fun clean() {
        release()
        context.deleteDatabase("subscriptions-$dataset.db").also {
            com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db")
        }
        java.io.File(context.filesDir, "datastore/settings-$dataset.preferences_pb").delete()
        context.deleteSharedPreferences("test-notification-offer-$dataset")
    }

    @Test fun delayedLookupStillOpensTheNotifiedDetails() = paused {
        ActivityScenario.launch<IsolatedMainActivity>(open(first.id)).whileAlive {
            awaitLookup()
            release()
            details(first.id)
        }
    }

    @Test fun warmDelayedLookupOpensTheNotifiedDetails() = paused {
        ActivityScenario.launch<IsolatedMainActivity>(main()).whileAlive { scenario ->
            home()
            scenario.onActivity { it.startActivity(open(first.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            scenario.followActivityIntent()
            awaitLookup()
            release()
            details(first.id)
        }
    }

    @Test fun newerRequestDuringLookupOpensOnceAndDropsTheStaleTarget() = paused {
        ActivityScenario.launch<IsolatedMainActivity>(open(first.id)).whileAlive { scenario ->
            awaitLookup(atLeast = 1)
            scenario.onActivity { it.startActivity(open(second.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            scenario.followActivityIntent()
            awaitLookup(atLeast = 2)
            release()
            details(second.id)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            home()
            compose.onNodeWithTag("details_${first.id}").assertDoesNotExist()
            compose.onNodeWithTag("details_${second.id}").assertDoesNotExist()
        }
    }

    @Test fun repeatedRequestDuringLookupDoesNotStackDetails() = paused {
        ActivityScenario.launch<IsolatedMainActivity>(open(first.id)).whileAlive { scenario ->
            awaitLookup(atLeast = 1)
            scenario.onActivity { it.startActivity(open(first.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            scenario.followActivityIntent()
            awaitLookup(atLeast = 2)
            release()
            details(first.id)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            home()
            compose.onNodeWithTag("details_${first.id}").assertDoesNotExist()
        }
    }

    @Test fun deletionDuringLookupReturnsHomeFromAnotherRoot() = paused {
        ActivityScenario.launch<IsolatedMainActivity>(main()).whileAlive { scenario ->
            home()
            compose.onNodeWithTag("tab_finance").performClick()
            compose.onNodeWithTag("tab_finance").assertIsSelected()
            scenario.onActivity { it.startActivity(open(first.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            scenario.followActivityIntent()
            awaitLookup()
            runBlocking(Dispatchers.IO) {
                val db = TrackerDatabaseFactory.open(context, dataset)
                try { db.subscriptions().delete(first.id) } finally { db.close() }
            }
            release()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("tab_subscriptions").fetchSemanticsNodes().any { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Selected) { false } } }
            compose.onNodeWithTag("tab_subscriptions").assertIsSelected()
            compose.onNodeWithTag("details_${first.id}").assertDoesNotExist()
        }
    }

    @Test fun lookupReleasedWhileStoppedStillOpensAfterRestart() = paused {
        ActivityScenario.launch<IsolatedMainActivity>(open(first.id)).whileAlive { scenario ->
            awaitLookup()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            release()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            details(first.id)
        }
    }

    @Test fun recreationDuringLookupKeepsTheRequestUntilItCanOpen() = paused {
        ActivityScenario.launch<IsolatedMainActivity>(open(first.id)).whileAlive { scenario ->
            awaitLookup(atLeast = 1)
            val seen = NavigationLookupPause.entered()
            scenario.recreate()
            awaitLookup(atLeast = seen + 1)
            release()
            details(first.id)
        }
    }

    private fun row(name: String, date: LocalDate) = SubscriptionEntity(
        id = UUID.randomUUID(), name = name, accountLabel = "Проверка гонки", priceMinor = 39900,
        intervalUnit = FrequencyUnit.MONTH, intervalCount = 1, state = SubscriptionState.ACTIVE,
        anchorYear = date.year, anchorMonth = date.monthValue, anchorDay = date.dayOfMonth,
        currentPeriodStartOverride = null, currentPeriodEnd = null, cancelledAccessEndExclusive = null,
        frozenCancelledPeriodStart = null, logoAssetId = null, catalogLogoId = null,
        createdAt = Instant.now(), updatedAt = Instant.now(), archivedAt = null,
    )

    private fun main() = Intent(context, IsolatedMainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .putExtra("test_dataset", dataset.toString())
        .putExtra(NavigationLookupPause.EXTRA, true)

    private fun open(id: UUID) = main()
        .setAction(NotificationRoute.OPEN)
        .setData(Uri.parse(NotificationRoute(dataset, id).identity))

    private fun paused(block: () -> Unit) {
        gate = NavigationLookupPause.arm()
        try { block() } finally { release() }
    }

    private fun awaitLookup(atLeast: Int = 1) {
        compose.waitUntil(15_000) { NavigationLookupPause.entered() >= atLeast }
    }

    private fun release() {
        gate?.complete(Unit)
        gate = null
        NavigationLookupPause.reset()
    }

    private fun details(id: UUID) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("details_$id").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Изменить").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun home() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("subscriptions_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bottom_navigation").assertExists()
        assertFalse(compose.onAllNodesWithText("Изменить").fetchSemanticsNodes().isNotEmpty())
    }
}
