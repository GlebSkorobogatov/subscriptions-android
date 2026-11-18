package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import org.junit.Rule
import org.junit.Test

class DetailsUiTest {
    @get:Rule val compose = createAndroidComposeRule<IsolatedMainActivity>()
    private fun createAndOpen(periodStart: Boolean = false) {
        compose.waitUntil(5000) { compose.onAllNodesWithText("Добавить подписку").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Добавить подписку").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("field_name").performTextInput("Сервис D03")
        compose.onNodeWithTag("field_amount").performTextInput("499,00")
        if (periodStart) {
            compose.onNodeWithText("Дополнительно").performScrollTo().performClick()
            compose.onNodeWithTag("field_start").performScrollTo().performTextInput(java.time.LocalDate.now().minusDays(1).toString())
        }
        compose.onNodeWithText("Сохранить").performScrollToIfScrollable().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("Сервис D03"))
        compose.onNodeWithText("Сервис D03").performClick()
        compose.openDetailsManagement()
    }
    private fun confirm() {
        compose.onNodeWithText("Подтвердить").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Подтвердить").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun detailsShowRealFieldsWithEditAndWithoutPaymentOrHistoryActions() {
        createAndOpen()
        compose.onNodeWithText("Подтвердить оплату").assertDoesNotExist()
        compose.onNodeWithText("История платежей и возвратов").assertDoesNotExist()
        compose.onNodeWithText("499 ₽").assertExists()
        compose.onNodeWithText("Редактировать").assertHasClickAction()
        compose.openDetailsManagement()
        compose.onNodeWithText("Проблема с оплатой").assertDoesNotExist()
    }
    @Test fun cancelledDeleteKeepsSubscriptionAndConfirmedDeleteReturnsHome() {
        createAndOpen()
        compose.onNodeWithText("Удалить подписку").performClick()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Сервис D03").assertExists()
        compose.openDetailsManagement()
        compose.onNodeWithText("Удалить подписку").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Удалить").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("tab_subscriptions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab_subscriptions").assertIsSelected()
        compose.onNodeWithText("Подписок пока нет").assertIsDisplayed()
    }
    @Test fun archiveProvidesMinimalPathBackToDetailsAndReactivation() {
        createAndOpen()
        compose.onNodeWithText("Архивировать").performClick()
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("subscriptions_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сервис D03").assertDoesNotExist()
        compose.openArchive()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Сервис D03").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сервис D03").performScrollTo().performClick()
        compose.onNodeWithText("Восстановить").assertHasClickAction().performClick()
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Активна").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Редактировать").assertHasClickAction()
    }
    @Test fun cancellationDefaultsToNearestExclusiveEndAndHidesProgressOnEndDay() {
        createAndOpen()
        compose.onNodeWithText("Отметить отмену").performClick()
        compose.onNodeWithText("Отметь отмену у поставщика. Приложение не отменяет подписку за тебя. Будущие ожидаемые платежи и напоминания будут исключены.").assertIsDisplayed()
        compose.onNodeWithTag("details_field_end").assertTextContains(java.time.LocalDate.now().toString())
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Отменена").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("details_progress").assertDoesNotExist()
        compose.openDetailsManagement()
        compose.onNodeWithText("Изменить окончание доступа").assertHasClickAction()
    }
    @Test fun knownAccessUsesDomainRemainingFraction() {
        createAndOpen(periodStart = true)
        compose.onNodeWithText("Отметить отмену").performClick()
        compose.onNodeWithTag("details_field_end").performTextReplacement(java.time.LocalDate.now().plusDays(3).toString())
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("details_progress").fetchSemanticsNodes().any {
            it.config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current == 0.75f
        } }
        compose.onNodeWithTag("details_progress").assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(0.75f,0f..1f))
    }

}
