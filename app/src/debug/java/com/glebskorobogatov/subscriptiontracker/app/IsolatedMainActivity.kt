package com.glebskorobogatov.subscriptiontracker.app

import android.os.Bundle
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsSource
import com.glebskorobogatov.subscriptiontracker.notifications.NotificationEnvironment
import com.glebskorobogatov.subscriptiontracker.notifications.NotificationRoute
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext

/** Instrumentation host only; launcher and release builds always use MainActivity. */
class IsolatedMainActivity : MainActivity() {
    private lateinit var dataset: UUID
    private var pauseNavigationLookup = false
    override fun onCreate(savedInstanceState: Bundle?) {
        dataset = savedInstanceState?.getString("test_dataset")?.let(UUID::fromString) ?: intent.getStringExtra("test_dataset")?.let(UUID::fromString) ?: UUID.randomUUID()
        pauseNavigationLookup = intent.getBooleanExtra(NavigationLookupPause.EXTRA, false)
        if (savedInstanceState == null && intent.getStringExtra("test_dataset") == null) {
            // Explicit new synthetic generation, before the test host can create its database.
            com.glebskorobogatov.subscriptiontracker.data.room.InitializationJournal(filesDir, dataset).locked { authorizeFresh() }
        }
        super.onCreate(savedInstanceState)
    }
    override fun createNotificationEnvironment(): com.glebskorobogatov.subscriptiontracker.notifications.NotificationEnvironment {
        val id = dataset
        val environment = com.glebskorobogatov.subscriptiontracker.notifications.AndroidNotificationEnvironment(applicationContext,
            active = { id }, request = {}, preferenceName = "test-notification-offer-$id")
        return object : NotificationEnvironment by environment {
            override fun dismiss(route: NotificationRoute) {
                environment.dismiss(route)
                DetailsOperationPause.current?.takeIf { it.dataset == id }?.notificationRead?.complete(route.subscription)
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("test_dataset", dataset.toString())
        super.onSaveInstanceState(outState)
    }
    override fun createSubscriptionsSource(): SubscriptionsSource {
        val context = applicationContext
        val id = dataset
        val pauseLookup = pauseNavigationLookup
        val source = SubscriptionsComposition(context) { id }
        return object : SubscriptionsSource {
            override fun activeDatasetId() = source.activeDatasetId()
            override fun settings() = source.settings()
            override fun finance() = source.finance()
            override fun archive() = source.archive()
            override fun today() = source.today()
            override fun details(id: UUID) = if (!pauseLookup) source.details(id) else flow {
                // The UI recheck is the only read held here. ViewModel.open keeps its own lookup.
                NavigationLookupPause.awaitTurn()
                emitAll(source.details(id))
            }
            override suspend fun <T> perform(block: suspend (com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases) -> T): T {
                val pause = DetailsOperationPause.current?.takeIf { it.dataset == id && it.claimed.compareAndSet(false, true) }
                pause?.entered?.complete(Unit)
                pause?.release?.await()
                val result = source.perform(block)
                if (pause?.loseResponse == true) throw IOException("Synthetic response lost after commit")
                return result
            }
            override fun observe(refreshes: kotlinx.coroutines.flow.Flow<Unit>) =
            source.observe(refreshes).onCompletion {
                // Upstream has already joined readers and closed both stores.
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    context.deleteSharedPreferences("test-notification-offer-$id")
                    context.deleteDatabase("subscriptions-$id.db")
                    // Teardown of this synthetic test generation, never production recovery.
                    for (extension in listOf("state", "next", "lock"))
                        File(context.filesDir, "${com.glebskorobogatov.subscriptiontracker.data.room.InitializationJournal.DIRECTORY}/$id.$extension").delete()
                    File(context.filesDir, "datastore/settings-$id.preferences_pb").delete()
                }
            }
        }
    }
}


// Arm after opening details, immediately before pressing the confirmation button.
internal class DetailsOperationPause(val dataset: UUID, val loseResponse: Boolean = false) {
    val claimed = AtomicBoolean()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val notificationRead = CompletableDeferred<UUID>()

    companion object {
        @Volatile var current: DetailsOperationPause? = null
    }
}
