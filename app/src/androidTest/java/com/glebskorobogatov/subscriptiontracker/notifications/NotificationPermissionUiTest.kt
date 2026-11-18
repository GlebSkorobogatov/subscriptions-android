package com.glebskorobogatov.subscriptiontracker.notifications

import android.Manifest
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import com.glebskorobogatov.subscriptiontracker.ui.performScrollToIfScrollable
import java.time.LocalDate
import org.junit.*
import org.junit.Assert.*

/** API33 CI starts this class on an explicitly ungranted fresh install before publisher tests. */
@FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class NotificationPermissionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun firstSaveExplainsBeforeRuntimeRequestDenialDoesNotBlockOrRepeat() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch<IsolatedMainActivity>(Intent(context,IsolatedMainActivity::class.java)).use { scenario ->
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Добавить подписку").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Подписка сохранена.",substring=true).assertDoesNotExist()
            assertNull(device.findObject(By.res("com.android.permissioncontroller","permission_allow_button")))
            compose.onNodeWithText("Добавить подписку").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("field_name").performTextInput("Проверка разрешения")
            compose.onNodeWithTag("field_amount").performTextInput("399")
            compose.onNodeWithTag("field_date").performScrollTo().performTextReplacement(LocalDate.now().plusDays(3).toString())
            compose.onNodeWithText("Сохранить").performScrollToIfScrollable().performClick()
            if (Build.VERSION.SDK_INT >= 33) {
                assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Подписка сохранена.",substring=true).fetchSemanticsNodes().isNotEmpty() }
                assertNull(device.findObject(By.res("com.android.permissioncontroller","permission_allow_button")))
                scenario.recreate() // The explanation survives; it does not automatically launch Android's prompt.
                compose.onNodeWithText("Подписка сохранена.",substring=true).assertExists()
                compose.onNodeWithText("Продолжить",useUnmergedTree=true).performClick()
                val deny = device.wait(Until.findObject(By.res("com.android.permissioncontroller","permission_deny_button")),5_000)
                assertNotNull("Actual Android runtime dialog must be shown",deny)
                deny.click()
                device.waitForIdle()
                assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
            scenario.recreate()
            compose.onNodeWithText("Подписка сохранена.",substring=true).assertDoesNotExist()
            compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("Проверка разрешения"))
            compose.onNodeWithText("Проверка разрешения").assertIsDisplayed()
            compose.onNodeWithTag("tab_settings").performClick()
            compose.onNodeWithText("Настройки уведомлений").performScrollTo().assertIsDisplayed()
            if (Build.VERSION.SDK_INT >= 33) compose.onNodeWithText("Требуется разрешение на уведомления").performScrollTo().assertIsDisplayed()
            assertNull(device.findObject(By.res("com.android.permissioncontroller","permission_allow_button")))
        }
    }
    /** API31 changes the actual Settings preference; API33 enters after the preceding denial. */
    @Test fun zSystemBlockRetainsPendingReasonsAndGrantReconciles(): Unit = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val publisher = AndroidReminderPublisher(context)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val dataset = java.util.UUID.randomUUID()
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset); val db = com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabaseFactory.open(context,dataset)
        val lease = com.glebskorobogatov.subscriptiontracker.data.settings.DatasetSettingsFactory.acquire(context,dataset)
        val scheduler = com.glebskorobogatov.subscriptiontracker.background.ReminderInfrastructureTest.RecordingScheduler()
        val generation = com.glebskorobogatov.subscriptiontracker.background.ReminderInfrastructureTest.TestGeneration(dataset)
        var instant = java.time.Instant.parse("2027-01-01T00:00:00Z")
        val sub = com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity(id = java.util.UUID.randomUUID(), name = "Проверка отказа", accountLabel = null, priceMinor = 39900, intervalUnit = com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit.MONTH, intervalCount = 1, state = com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState.ACTIVE, anchorYear = 2027, anchorMonth = 1, anchorDay = 5, currentPeriodStartOverride = null, currentPeriodEnd = null, cancelledAccessEndExclusive = null, frozenCancelledPeriodStart = null, logoAssetId = null, catalogLogoId = null, createdAt = instant, updatedAt = instant, archivedAt = null)
        val runtime = com.glebskorobogatov.subscriptiontracker.background.ReminderRuntime(context,scheduler,generation,publisher,
            { instant },{ java.time.ZoneOffset.UTC })
        val selector = By.res(java.util.regex.Pattern.compile("(android|com.android.settings):id/switch_widget"))
        suspend fun await(label: String, predicate: () -> Boolean) {
            try { kotlinx.coroutines.withTimeout(5_000) { while (!predicate()) kotlinx.coroutines.delay(25) } }
            catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("$label: ${publisher.access()}",e) }
        }
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.subscriptions().insert(sub) }
            publisher.createChannels()
            if (Build.VERSION.SDK_INT >= 33) {
                assertEquals("API33 job starts ungranted; the preceding UI scenario denies permission",
                    PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val toggle = device.wait(Until.findObject(selector),5_000)
                assertNotNull("Actual app notification control",toggle); toggle.click()
            }
            await("System block must be observable") { !publisher.access().appEnabled }
            runtime.reconcile(); val task = scheduler.tasks.single()
            instant = java.time.Instant.parse("2027-01-04T10:00:00Z")
            assertEquals(com.glebskorobogatov.subscriptiontracker.data.reminders.PublicationResult.UNAVAILABLE,
                runtime.process(dataset,sub.id,task.fingerprint))
            assertTrue(db.deliveries().all().all { it.state == com.glebskorobogatov.subscriptiontracker.data.room.DeliveryState.PENDING && it.deliveredAt == null })
            assertTrue(manager.activeNotifications.none { it.tag?.contains(dataset.toString()) == true })
            if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
            else { device.wait(Until.findObject(selector),5_000)!!.click(); device.pressBack() }
            await("Grant must be observable") { publisher.access().usable }
            runtime.reconcile(); val recovered = scheduler.tasks.single()
            assertEquals(com.glebskorobogatov.subscriptiontracker.data.reminders.PublicationResult.PUBLISHED,
                runtime.process(dataset,sub.id,recovered.fingerprint))
            await("Actual system notification after grant") { manager.activeNotifications.any { it.tag?.contains(dataset.toString()) == true } }
            assertTrue(db.deliveries().all().all { it.state == com.glebskorobogatov.subscriptiontracker.data.room.DeliveryState.DELIVERED })
        } finally {
            if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
            else if (!publisher.access().appEnabled) { device.wait(Until.findObject(selector),5_000)?.click(); device.pressBack() }
            manager.activeNotifications.filter { it.tag?.contains(dataset.toString()) == true }.forEach { manager.cancel(it.tag,it.id) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.close() }; lease.close()
            context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }
            java.io.File(context.filesDir,"datastore/settings-$dataset.preferences_pb").delete()
        }
    }

}
