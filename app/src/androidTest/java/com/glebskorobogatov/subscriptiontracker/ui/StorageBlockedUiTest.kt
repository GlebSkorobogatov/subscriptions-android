package com.glebskorobogatov.subscriptiontracker.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.glebskorobogatov.subscriptiontracker.app.StorageFailureTestActivity
import com.glebskorobogatov.subscriptiontracker.notifications.NotificationRoute
import com.glebskorobogatov.subscriptiontracker.R
import java.util.UUID
import org.junit.Rule
import org.junit.Test

class StorageBlockedUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun intent(temporary: Boolean = false) = Intent(context, StorageFailureTestActivity::class.java)
        .putExtra("transient", temporary)
    private fun blocked() {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("storage_blocked").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("storage_blocked").assertIsDisplayed()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithTag("subscription_editor").assertDoesNotExist()
    }
    @Test fun incompatibleFailureDrawsDespiteSettingsLoadingAndRemainsBlockedAfterRestoreAndNotification() {
        ActivityScenario.launch<StorageFailureTestActivity>(intent()).use { scenario ->
            blocked()
            compose.onNodeWithText(context.getString(R.string.storage_incompatible)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.retry_loading)).assertDoesNotExist()
            scenario.recreate()
            blocked()
            scenario.onActivity { activity ->
                activity.startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .setAction(NotificationRoute.OPEN).setData(android.net.Uri.parse(NotificationRoute(UUID(0, 1), UUID(0, 2)).identity)))
            }
            blocked()
        }
    }
    @Test fun temporaryFailureHasExplicitRetryAndCanRecover() {
        ActivityScenario.launch<StorageFailureTestActivity>(intent(true)).use {
            blocked()
            compose.onNodeWithText(context.getString(R.string.retry_loading)).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("subscriptions_screen").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("storage_blocked").assertDoesNotExist()
        }
    }
}
