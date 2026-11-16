package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import org.junit.Rule
import org.junit.Test

class DetailsUiTest {
    @get:Rule val compose = createAndroidComposeRule<IsolatedMainActivity>()
    private fun createAndOpen(manual: Boolean = false, periodStart: Boolean = false) {
        compose.waitUntil(5000) { compose.onAllNodesWithText("Добавить первую подписку").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Добавить первую подписку").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("field_name").performTextInput("Сервис D03")
        compose.onNodeWithTag("field_amount").performTextInput("499,00")
        if (manual) {
            compose.onNodeWithText("Ручное продление").performScrollTo().performClick()
            compose.onNodeWithTag("field_date").performScrollTo().performTextReplacement(java.time.LocalDate.now().minusDays(2).toString())
        }
        if (periodStart) {
            compose.onNodeWithText("Дополнительно").performScrollTo().performClick()
            compose.onNodeWithTag("field_start").performScrollTo().performTextInput(java.time.LocalDate.now().minusDays(1).toString())
        }
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("Сервис D03"))
        compose.onNodeWithText("Сервис D03").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("details_management").fetchSemanticsNodes().isNotEmpty() }
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
        compose.onNodeWithText("499,00 RUB").assertExists()
        compose.onAllNodesWithText("Изменить").onFirst().assertHasClickAction()
        compose.openDetailsManagement()
        compose.onNodeWithText("Проблема с оплатой").assertDoesNotExist()
    }
    @Test fun cancelledDeleteKeepsSubscriptionAndConfirmedDeleteReturnsHome() {
        createAndOpen()
        compose.onNodeWithText("Удалить подписку").performScrollTo().performClick()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Сервис D03").assertExists()
        compose.onNodeWithText("Удалить подписку").performScrollTo().performClick()
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("tab_subscriptions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab_subscriptions").assertIsSelected()
        compose.onNodeWithText("Подписок пока нет").assertIsDisplayed()
    }
    @Test fun archiveProvidesMinimalPathBackToDetailsAndReactivation() {
        createAndOpen()
        compose.onNodeWithText("Архивировать").performScrollTo().performClick()
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Возобновить подписку").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("tab_archive").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Сервис D03").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сервис D03").performScrollTo().performClick()
        compose.openDetailsManagement()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Возобновить подписку").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Возобновить подписку").performScrollTo().performClick()
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("details_management").fetchSemanticsNodes().isNotEmpty() }
        compose.openDetailsManagement()
    }
    @Test fun unknownAccessEndNeverInventsCountdownOrProgress() {
        createAndOpen()
        compose.onNodeWithText("Продление отключено").performScrollTo().performClick()
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Дата окончания доступа неизвестна").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("details_progress").assertDoesNotExist()
    }
    @Test fun knownAccessUsesDomainRemainingFraction() {
        createAndOpen(periodStart = true)
        compose.onNodeWithText("Продление отключено").performScrollTo().performClick()
        compose.onNodeWithTag("details_field_end").performTextReplacement(java.time.LocalDate.now().plusDays(3).toString())
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("details_progress").fetchSemanticsNodes().any {
            it.config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current == 0.75f
        } }
        compose.onNodeWithTag("details_progress").assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(0.75f,0f..1f))
    }

}
