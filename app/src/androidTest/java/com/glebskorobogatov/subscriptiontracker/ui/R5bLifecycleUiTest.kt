package com.glebskorobogatov.subscriptiontracker.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import com.glebskorobogatov.subscriptiontracker.ui.design.formatTrackerRub
import java.io.File
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real editor, details and archive path. Lifecycle commands stay in the existing ViewModel. */
class R5bLifecycleUiTest {
    @get:Rule val compose = createAndroidComposeRule<IsolatedMainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @After fun restoreWindow() {
        runCatching { compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } }
    }

    @Test fun activeMenuMatchesTheMatrixAndDismissKeepsTheRecord() {
        create("Матрица R5b", "150")
        val id = openDetails("Матрица R5b")
        compose.onNodeWithText("Редактировать").assertHasClickAction()
        compose.onNodeWithText("Возобновить").assertDoesNotExist()
        compose.onNodeWithText("Восстановить").assertDoesNotExist()
        val root = compose.onNodeWithTag("details_$id").fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag("details_icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val price = compose.onNodeWithTag("details_price", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(icon.left <= price.left + 1f)
        assertTrue(price.left < root.center.x)
        compose.onNodeWithText(formatTrackerRub(15000)).assertExists()
        compose.openDetailsManagement()
        compose.onNodeWithText("Отметить отмену").assertHasClickAction()
        compose.onNodeWithText("Архивировать").assertHasClickAction()
        compose.onNodeWithText("Удалить подписку").assertHasClickAction()
        compose.onNodeWithText("Изменить окончание доступа").assertDoesNotExist()
        compose.onNodeWithText("Отметить отмену").performClick()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Активна").assertIsDisplayed()
        compose.openDetailsManagement()
        compose.onNodeWithText("Архивировать").performClick()
        compose.onNodeWithText("Подписка исчезнет из списка, статистики и напоминаний. Название, цена, периодичность и значок сохранятся в архиве.").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Активна").assertIsDisplayed()
        device.pressBack()
        compose.onNodeWithTag("subscriptions_screen").assertExists()
        compose.onNodeWithText("Матрица R5b").assertExists()
    }

    @Test fun cancelUsesTheNextDateAndTheIndicatorStopsOnTheEndDay() {
        create("Отмена R5b", "200")
        openDetails("Отмена R5b")
        compose.openDetailsManagement()
        compose.onNodeWithText("Отметить отмену").performClick()
        val today = LocalDate.now().toString()
        compose.onNodeWithTag("details_field_end").assertTextContains(today)
        compose.onNodeWithTag("details_date_picker_end").performScrollTo().performClick()
        compose.onNodeWithTag("editor_date_sheet").assertExists()
        device.pressBack()
        compose.onNodeWithTag("editor_date_sheet").assertDoesNotExist()
        compose.onNodeWithTag("details_field_end").assertTextContains(today)
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Активна").assertIsDisplayed()
        compose.openDetailsManagement()
        compose.onNodeWithText("Отметить отмену").performClick()
        confirm()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Отменена").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("details_progress", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Отменена").assertIsDisplayed()
        compose.onNodeWithText("Возобновить").assertHasClickAction()
        compose.openDetailsManagement()
        compose.onNodeWithText("Редактировать").assertHasClickAction()
        compose.onNodeWithText("Изменить окончание доступа").performClick()
        compose.onNodeWithTag("details_field_end").performTextReplacement(LocalDate.now().plusDays(4).toString())
        confirm()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("details_progress", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Отменена").assertIsDisplayed()
        compose.onNodeWithText("Активна").assertDoesNotExist()
        device.pressBack()
        compose.onNodeWithTag("tab_finance").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Ожидаемых списаний пока нет").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun archiveReturnsHomeAndCustomRestoreKeepsUuidAppearanceAndPeriod() {
        create("Архив R5b", "320", catalog = true)
        val id = openDetails("Архив R5b")
        compose.openDetailsManagement()
        compose.onNodeWithText("Архивировать").performClick()
        compose.onNodeWithText("Подтвердить").performTouchInput { doubleClick() }
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("subscriptions_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Архив R5b").assertDoesNotExist()
        compose.openArchive()
        compose.onAllNodesWithTag("archive_$id").assertCountEquals(1)
        compose.onNodeWithTag("archive_$id").assert(hasText("Архив R5b")).assert(hasText(formatTrackerRub(32000)))
        compose.onNodeWithTag("subscription_progress", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("archive_$id").performClick()
        compose.onNodeWithTag("details_$id").assertExists()
        compose.onNodeWithText("Восстановить").performClick()
        compose.onNodeWithTag("frequency_custom").performScrollTo().performClick()
        compose.onNodeWithTag("details_field_count").performScrollTo().performTextReplacement("1")
        compose.onNodeWithTag("frequency_unit_MONTH").performScrollTo().performClick()
        compose.onNodeWithTag("frequency_custom").assertIsSelected()
        compose.onNodeWithTag("frequency_month").assertIsNotSelected()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("frequency_custom").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("frequency_month").assertIsNotSelected()
        compose.onNodeWithTag("details_field_count").assertTextContains("1")
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("В архиве").assertIsDisplayed()
        compose.onNodeWithText("Восстановить").performClick()
        compose.onNodeWithTag("details_field_amount").performScrollTo().performTextReplacement("640")
        compose.onNodeWithTag("frequency_custom").performScrollTo().performClick()
        compose.onNodeWithTag("details_field_count").performScrollTo().performTextReplacement("6")
        compose.onNodeWithTag("frequency_unit_MONTH").performScrollTo().performClick()
        compose.onNodeWithTag("frequency_month").assertIsNotSelected()
        compose.onNodeWithTag("details_field_date").performScrollTo().performTextReplacement(LocalDate.now().plusDays(3).toString())
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Активна").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("details_$id").assertExists()
        compose.onNodeWithText(formatTrackerRub(64000)).assertExists()
        compose.onNodeWithText("Редактировать").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("editor_choose_catalog").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor_choose_catalog").performClick()
        compose.onNodeWithTag("catalog_generic-ai").performScrollTo().assertIsSelected()
        device.pressBack()
        compose.onNodeWithTag("subscription_editor").assertExists()
        device.pressBack()
        compose.onNodeWithTag("details_$id").assertExists()
        device.pressBack()
        compose.onNodeWithTag("archive_screen").assertExists()
        device.pressBack()
        compose.onNodeWithTag("tab_subscriptions").performClick()
        compose.onNodeWithTag("subscription_$id").assertExists()
        compose.onNodeWithTag("tab_finance").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("finance_30_total").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_30_total"))
        compose.onNodeWithTag("finance_30_total").assertTextEquals("640,00 RUB")
    }

    @Test fun deletingOneSubscriptionLeavesTheOtherUntouched() {
        create("Первая R5b", "110")
        create("Вторая R5b", "220")
        openDetails("Первая R5b")
        compose.openDetailsManagement()
        compose.onNodeWithText("Удалить подписку").performClick()
        compose.onNodeWithText("Удаление необратимо. Подписка и все её локальные данные будут удалены. Другие подписки не изменятся.").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Первая R5b").assertExists()
        compose.openDetailsManagement()
        compose.onNodeWithText("Удалить подписку").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("subscriptions_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Первая R5b").assertDoesNotExist()
        compose.onNodeWithText("Вторая R5b").assertExists()
        compose.onNodeWithText(formatTrackerRub(22000)).assertExists()
    }

    @Test fun recreationKeepsTheCancellationFormAndBackClosesTheCalendarFirst() {
        create("Поворот R5b", "80")
        openDetails("Поворот R5b")
        compose.openDetailsManagement()
        compose.onNodeWithText("Отметить отмену").performClick()
        val today = LocalDate.now().toString()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("details_field_end").assertTextContains(today)
        compose.onNodeWithText("Отметь отмену у поставщика. Приложение не отменяет подписку за тебя. Будущие ожидаемые платежи и напоминания будут исключены.").assertExists()
        compose.onNodeWithTag("details_date_picker_end").performScrollTo().performClick()
        compose.onNodeWithTag("editor_date_sheet").assertExists()
        device.pressBack()
        compose.onNodeWithTag("details_field_end").assertTextContains(today)
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Активна").assertIsDisplayed()
    }

    @Test fun collapsedIncompatibleAnchorIsRevealedAndCanBeCorrected() {
        create("Календарная опора", "175")
        openDetails("Календарная опора")
        compose.openDetailsManagement()
        compose.onNodeWithText("Отметить отмену").performClick()
        confirm()
        compose.onNodeWithText("Возобновить").performClick()
        val nextMonth = LocalDate.now().plusMonths(1).withDayOfMonth(15)
        compose.onNodeWithTag("details_field_date").performScrollTo().performTextReplacement(nextMonth.toString())
        compose.onNodeWithTag("details_extra").performScrollTo().performClick()
        compose.onNodeWithTag("details_field_anchorDay").performScrollTo().performTextReplacement("31")
        compose.onNodeWithTag("details_extra").performScrollTo().performClick()
        compose.onNodeWithTag("details_field_anchorDay").assertDoesNotExist()
        compose.onNodeWithTag("details_submit").performClick()
        compose.onNodeWithTag("details_field_anchorDay").performScrollTo().assertTextContains("31")
        compose.onNodeWithText("Опорный день должен соответствовать выбранной дате и периодичности. Исправьте его или оставьте поле пустым.")
            .assertExists()
        compose.onNodeWithTag("details_field_anchorDay").performTextReplacement("")
        compose.onNodeWithTag("details_submit").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("details_dialog").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Активна").assertIsDisplayed()
    }

    @Test fun modalFormBlocksTheBackgroundBackButtonAndNativeBackClosesOnlyTheForm() {
        create("Модальная отмена", "180")
        val id = openDetails("Модальная отмена")
        val back = compose.onNodeWithContentDescription("Назад").fetchSemanticsNode().boundsInWindow.center
        compose.openDetailsManagement()
        compose.onNodeWithText("Отметить отмену").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithContentDescription("Назад").assertIsNotEnabled()
        // A direct touch where the background action was must never navigate behind the window.
        device.click(back.x.toInt(), back.y.toInt())
        compose.waitForIdle()
        compose.onNodeWithTag("details_$id").assertExists()
        if (compose.onAllNodesWithTag("details_dialog").fetchSemanticsNodes().isEmpty()) {
            compose.openDetailsManagement()
            compose.onNodeWithText("Отметить отмену").performClick()
            compose.waitForIdle()
        }
        device.pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("details_dialog").assertDoesNotExist()
        compose.onNodeWithTag("details_$id").assertExists()
        compose.onNodeWithText("Активна").assertExists()
    }

    @Test fun largeLandscapeMenuAndPrimaryStayReachable() {
        create("Крупно R5b", "90")
        openDetails("Крупно R5b")
        orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Редактировать").fetchSemanticsNodes().isNotEmpty() }
        val primary = compose.onNodeWithTag("details_primary").assertIsDisplayed().fetchSemanticsNode()
        assertTrue(primary.boundsInRoot.height >= 48f * compose.density.density)
        compose.onNodeWithTag("details_menu").assertIsDisplayed().performClick()
        compose.onNodeWithText("Отметить отмену").assertIsDisplayed()
        compose.onNodeWithText("Архивировать").assertIsDisplayed()
        compose.onNodeWithText("Удалить подписку").assertIsDisplayed()
        device.pressBack()
        compose.onNodeWithText("Отметить отмену").assertDoesNotExist()
    }

    @Test fun captureDetailsArchiveAndLifecycleFrames() {
        compose.openArchive()
        compose.waitForIdle(); shot("archive-empty")
        device.pressBack()
        compose.onNodeWithTag("tab_subscriptions").performClick()
        create("Кадр R5b", "499", catalog = true)
        openDetails("Кадр R5b")
        compose.waitForIdle(); shot("details-active")
        compose.openDetailsManagement()
        compose.waitForIdle(); shot("menu-active")
        compose.onNodeWithText("Отметить отмену").performClick()
        compose.waitForIdle(); shot("cancel-dialog")
        compose.onNodeWithTag("details_date_picker_end").performScrollTo().performClick()
        compose.onNodeWithTag("editor_date_sheet").assertExists()
        compose.waitForIdle(); shot("end-calendar")
        device.pressBack()
        confirm()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Отменена").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle(); shot("details-cancelled-ended")
        compose.openDetailsManagement()
        compose.waitForIdle(); shot("menu-cancelled")
        compose.onNodeWithText("Изменить окончание доступа").performClick()
        compose.onNodeWithTag("details_field_end").performTextReplacement(LocalDate.now().plusDays(10).toString())
        confirm()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("details_progress", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle(); shot("details-cancelled")
        compose.onNodeWithText("Возобновить").performClick()
        compose.waitForIdle(); shot("resume-form")
        compose.onNodeWithText("Отмена").performClick()
        compose.openDetailsManagement()
        compose.onNodeWithText("Архивировать").performClick()
        compose.waitForIdle(); shot("archive-confirm")
        confirm()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("subscriptions_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.openArchive()
        compose.waitForIdle(); shot("archive-filled")
        compose.onNode(hasText("Кадр R5b") and hasClickAction()).performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Восстановить").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle(); shot("details-archived")
        compose.openDetailsManagement()
        compose.waitForIdle(); shot("menu-archived")
        device.pressBack()
        compose.onNodeWithText("Восстановить").performClick()
        compose.waitForIdle(); shot("restore-form")
        compose.onNodeWithText("Отмена").performClick()
        compose.openDetailsManagement()
        compose.onNodeWithText("Удалить подписку").performClick()
        compose.waitForIdle(); shot("delete-confirm")
        compose.onNodeWithText("Отмена").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Удаление необратимо. Подписка и все её локальные данные будут удалены. Другие подписки не изменятся.").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("archive_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("settings_screen").assertExists()
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_theme_DARK"))
        compose.onNodeWithTag("settings_theme_DARK").performClick()
        compose.openArchive()
        compose.onNode(hasText("Кадр R5b") and hasClickAction()).performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Восстановить").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle(); shot("details-dark")
        orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Восстановить").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle(); shot("details-landscape")
        compose.onNodeWithTag("details_menu").performClick()
        compose.onNodeWithText("Удалить подписку").assertIsDisplayed()
        compose.waitForIdle(); shot("menu-landscape")
    }

    @Test fun captureWhenSystemFontIsEnlarged() {
        if (compose.activity.resources.configuration.fontScale < 1.5f) return
        create("Шрифт R5b", "250")
        openDetails("Шрифт R5b")
        compose.onNodeWithText("Редактировать").assertIsDisplayed()
        compose.waitForIdle(); shot("details-system-font-2")
        compose.openDetailsManagement()
        compose.onNodeWithText("Отметить отмену").assertIsDisplayed()
        compose.onNodeWithText("Архивировать").assertIsDisplayed()
        compose.onNodeWithText("Удалить подписку").assertIsDisplayed()
        compose.waitForIdle(); shot("menu-system-font-2")
        orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        compose.onNodeWithTag("details_primary").assertIsDisplayed()
        compose.waitForIdle(); shot("details-system-font-2-landscape")
    }

    private fun create(name: String, amount: String, catalog: Boolean = false) {
        compose.waitUntil(8_000) { compose.onAllNodesWithContentDescription("Добавить подписку").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Добавить подписку").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        if (catalog) {
            compose.onNodeWithTag("editor_choose_catalog").performClick()
            compose.onNodeWithTag("catalog_generic-ai").performScrollTo().performClick()
        }
        compose.onNodeWithTag("field_name").performTextInput(name)
        compose.onNodeWithTag("field_amount").performTextInput(amount)
        compose.onNodeWithTag("editor_save").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
    }

    private fun openDetails(name: String): String {
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText(name))
        val tag = compose.onNode(hasText(name) and hasTestTagPrefix("subscription_")).fetchSemanticsNode().config[SemanticsProperties.TestTag]
        compose.onNodeWithTag(tag).performClick()
        val id = tag.removePrefix("subscription_")
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("details_$id").fetchSemanticsNodes().isNotEmpty() }
        return id
    }

    private fun confirm() {
        compose.onNodeWithText("Подтвердить").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Подтвердить").fetchSemanticsNodes().isEmpty() }
    }

    private fun orient(request: Int, expected: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = request }
        compose.waitUntil(10_000) {
            var matches = false
            compose.activityRule.scenario.onActivity { matches = it.resources.configuration.orientation == expected }
            matches
        }
    }

    private fun shot(name: String) {
        val file = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "r5b-$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        assertTrue(file.length() > 0)
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag prefix $prefix") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }
}

/** Deletes one of two owners through the details screen and keeps the shared file. */
class R5bSharedAssetUiTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dataset = java.util.UUID.randomUUID()
    private val image = java.util.UUID.randomUUID()
    private val first = java.util.UUID.randomUUID()
    private val second = java.util.UUID.randomUUID()

    @org.junit.After fun clean() {
        context.deleteDatabase("subscriptions-$dataset.db")
        com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db")
        File(context.filesDir, "assets/$dataset").deleteRecursively()
        File(context.filesDir, "datastore/settings-$dataset.preferences_pb").delete()
    }

    @Test fun confirmedDeleteKeepsTheSharedImageForTheRemainingSubscription() {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { seedSharedImage() }
        val file = File(context.filesDir, "assets/$dataset/images/$image.png")
        val intent = android.content.Intent(context, IsolatedMainActivity::class.java).putExtra("test_dataset", dataset.toString())
        androidx.test.core.app.ActivityScenario.launch<IsolatedMainActivity>(intent).use {
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("subscription_$first").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("subscription_$first").performClick()
            compose.openDetailsManagement()
            compose.onNodeWithText("Удалить подписку").performClick()
            compose.onNodeWithText("Удалить").performClick()
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("subscriptions_screen").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("subscription_$first").assertDoesNotExist()
            compose.onNodeWithTag("subscription_$second").assertExists()
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val check = com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabaseFactory.open(context, dataset)
                try {
                    assertNull(check.subscriptions().find(first))
                    assertEquals(image, check.subscriptions().find(second)?.logoAssetId)
                    assertNotNull(check.assets().find(image))
                } finally { check.close() }
            }
            assertTrue(file.exists())
        }
    }

    private fun seedSharedImage() {
        val now = java.time.Instant.now()
        val day = LocalDate.now().plusDays(4)
        val file = File(context.filesDir, "assets/$dataset/images/$image.png")
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset)
        val db = com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabaseFactory.open(context, dataset)
        val placeholder = ByteArray(8)
        try {
            db.assets().insert(com.glebskorobogatov.subscriptiontracker.data.room.AssetEntity(
                image, "assets/$dataset/images/$image.png", "image/png", placeholder.size.toLong(),
                "a".repeat(64), now))
            val row = com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity(
                first, "Первый владелец", null, 10000, com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit.MONTH, 1,
                com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState.ACTIVE,
                day.year, day.monthValue, day.dayOfMonth, null, null, null, null, image, null, now, now, null)
            db.subscriptions().insert(row)
            db.subscriptions().insert(row.copy(id = second, name = "Второй владелец"))
        } finally { db.close() }
        file.parentFile!!.mkdirs()
        android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val sized = com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabaseFactory.open(context, dataset)
        try {
            val stored = requireNotNull(sized.assets().find(image))
            sized.assets().update(stored.copy(sizeBytes = file.length(), sha256 = hash))
        } finally { sized.close() }
    }
}

/** Activity configuration fontScale=2. System font settings stay untouched, so the Compose rule cannot deadlock. */
class R5bFontScaleUiTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<com.glebskorobogatov.subscriptiontracker.app.LargeFontReviewActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After fun portrait() {
        runCatching { compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } }
    }

    @Test fun menuAndCancelFormStayReachableAtFontTwo() {
        val dialog = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            if (dialog.value) com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme {
                com.glebskorobogatov.subscriptiontracker.ui.details.DetailsActionDialog(
                    com.glebskorobogatov.subscriptiontracker.ui.preview.DetailsEditorPreviewData.details.copy(
                        action = com.glebskorobogatov.subscriptiontracker.ui.details.DetailsAction.Disable,
                        fields = mapOf("end" to "2027-03-31")),
                    { _, _ -> }, {}, {}, {})
            } else com.glebskorobogatov.subscriptiontracker.ui.preview.DetailsDemo()
        }
        compose.onNodeWithTag("details_primary").assertIsDisplayed()
        val primary = compose.onNodeWithTag("details_primary").fetchSemanticsNode()
        assertTrue(primary.boundsInRoot.height >= 48f * compose.density.density)
        compose.waitForIdle(); shot("details-font-2")
        compose.onNodeWithTag("details_menu").performClick()
        compose.onNodeWithText("Отметить отмену").assertIsDisplayed()
        compose.onNodeWithText("Архивировать").assertIsDisplayed()
        compose.onNodeWithText("Удалить подписку").assertIsDisplayed()
        val item = compose.onNodeWithText("Удалить подписку").fetchSemanticsNode()
        assertTrue(item.boundsInRoot.height >= 48f * compose.density.density)
        compose.waitForIdle(); shot("menu-font-2")
        compose.runOnIdle { dialog.value = true }
        compose.onNodeWithText("Подтвердить").assertIsDisplayed()
        compose.onNodeWithText("Отмена").assertIsDisplayed()
        compose.onNodeWithTag("details_field_end").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("details_date_picker_end").performScrollTo().assertIsDisplayed()
        compose.waitForIdle(); shot("cancel-font-2")
    }

    @Test fun cancelFormStaysReachableInLandscapeAtFontTwo() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) {
            var matches = false
            compose.activityRule.scenario.onActivity { matches = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            matches
        }
        compose.setContent {
            com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme {
                com.glebskorobogatov.subscriptiontracker.ui.details.DetailsActionDialog(
                    com.glebskorobogatov.subscriptiontracker.ui.preview.DetailsEditorPreviewData.details.copy(
                        action = com.glebskorobogatov.subscriptiontracker.ui.details.DetailsAction.Disable,
                        fields = mapOf("end" to "2027-03-31")),
                    { _, _ -> }, {}, {}, {})
            }
        }
        compose.onNodeWithText("Подтвердить").assertIsDisplayed()
        compose.onNodeWithText("Отмена").assertIsDisplayed()
        compose.onNodeWithTag("details_date_picker_end").performScrollTo().assertIsDisplayed()
        compose.waitForIdle(); shot("cancel-landscape-font-2")
    }

    @Test fun landscapeFontTwoCanFocusAFieldAndReachBothActionsWithTheKeyboard() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) {
            var landscape = false
            compose.activityRule.scenario.onActivity {
                landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    it.resources.configuration.fontScale >= 1.9f
            }
            landscape
        }
        var cancelled = false
        compose.setContent {
            val end = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("2027-03-31") }
            com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme {
                com.glebskorobogatov.subscriptiontracker.ui.details.DetailsActionDialog(
                    com.glebskorobogatov.subscriptiontracker.ui.preview.DetailsEditorPreviewData.details.copy(
                        action = com.glebskorobogatov.subscriptiontracker.ui.details.DetailsAction.Disable,
                        fields = mapOf("end" to end.value)),
                    { _, value -> end.value = value }, {}, { cancelled = true }, {})
            }
        }
        val end = compose.onNodeWithTag("details_field_end")
        end.performScrollTo().performTouchInput { click() }
        end.assertIsFocused().performTextInput("1")
        for (action in listOf(compose.onNodeWithTag("details_submit"), compose.onNodeWithText("Отмена"))) {
            if (!action.isDisplayed()) action.performScrollTo()
            action.assertIsDisplayed().assertIsEnabled()
        }
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    private fun shot(name: String) {
        val file = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "r5b-$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        assertTrue(file.length() > 0)
    }
}
