package com.glebskorobogatov.subscriptiontracker.app

import android.os.Bundle
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext

/** Instrumentation host only; launcher and release builds always use MainActivity. */
class IsolatedMainActivity : MainActivity() {
    private lateinit var dataset: UUID
    override fun onCreate(savedInstanceState: Bundle?) {
        dataset = savedInstanceState?.getString("test_dataset")?.let(UUID::fromString) ?: intent.getStringExtra("test_dataset")?.let(UUID::fromString) ?: UUID.randomUUID()
        super.onCreate(savedInstanceState)
    }
    override fun createNotificationEnvironment(): com.glebskorobogatov.subscriptiontracker.notifications.NotificationEnvironment {
        val id = dataset
        return com.glebskorobogatov.subscriptiontracker.notifications.AndroidNotificationEnvironment(applicationContext,
            active = { id }, request = {}, preferenceName = "test-notification-offer-$id")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("test_dataset", dataset.toString())
        super.onSaveInstanceState(outState)
    }
    override fun createSubscriptionsSource(): SubscriptionsSource {
        val context = applicationContext
        val id = dataset
        val source = SubscriptionsComposition(context) { id }
        return object : SubscriptionsSource {
            override fun activeDatasetId() = source.activeDatasetId()
            override fun settings() = source.settings()
            override fun finance() = source.finance()
            override fun archive() = source.archive()
            override fun today() = source.today()
            override fun details(id: UUID) = source.details(id)
            override suspend fun <T> perform(block: suspend (com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases) -> T): T = source.perform(block)
            override fun observe(refreshes: kotlinx.coroutines.flow.Flow<Unit>) =
            source.observe(refreshes).onCompletion {
                // Upstream has already joined readers and closed both stores.
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    context.deleteSharedPreferences("test-notification-offer-$id")
                    context.deleteDatabase("subscriptions-$id.db")
                    File(context.filesDir, "datastore/settings-$id.preferences_pb").delete()
                }
            }
        }
    }
}
