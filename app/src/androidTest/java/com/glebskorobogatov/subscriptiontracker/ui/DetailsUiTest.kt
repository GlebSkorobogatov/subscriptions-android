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
        compose.waitUntil(5000) { compose.onAllNodesWithText("Подтвердить оплату").fetchSemanticsNodes().isNotEmpty() }
        compose.openDetailsManagement()
    }
    private fun confirm() {
        compose.onNodeWithText("Подтвердить").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Подтвердить").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun detailsShowRealFieldsAndPaymentRequiresExplicitConfirmation() {
        createAndOpen()
        compose.onNodeWithText("Сервис D03").assertExists()
        compose.onNodeWithText("499,00 RUB").assertExists()
        compose.onNodeWithText("Подтверждённых платежей нет").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Подтвердить оплату").performScrollTo().performClick()
        compose.onNodeWithTag("details_field_paid").assertExists()
        compose.onNodeWithTag("details_field_amount").assertExists()
        compose.onNodeWithTag("details_field_next").assertDoesNotExist()
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("details_field_paid").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Подтверждённых платежей нет").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Подтверждённых платежей нет").assertDoesNotExist()
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
        compose.waitUntil(5000) { compose.onAllNodesWithText("Подтвердить оплату").fetchSemanticsNodes().isNotEmpty() }
        compose.openDetailsManagement()
    }
    @Test fun paymentOperationAndFieldsSurviveRecreation() {
        createAndOpen()
        compose.onNodeWithText("Подтвердить оплату").performScrollTo().performClick()
        compose.onNodeWithTag("details_field_amount").performTextReplacement("599,00")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("details_field_amount").assertTextContains("599,00")
        compose.onNodeWithText("Отмена").performClick()
    }
    @Test fun markIssueThenResolveDateDoesNotCreatePayment() {
        createAndOpen()
        compose.onNodeWithText("Проблема с оплатой").performScrollTo().performClick()
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Разрешить проблему изменением даты").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Подтверждённых платежей нет").assertExists()
        compose.onNodeWithText("Разрешить проблему изменением даты").performScrollTo().performClick()
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Разрешить проблему изменением даты").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Подтверждённых платежей нет").assertExists()
    }
    @Test fun lateManualPaymentCanKeepFuturePrice() {
        createAndOpen(manual = true)
        compose.onNodeWithText("Ожидает продления").assertExists()
        compose.onNodeWithText("Подтвердить оплату").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("details_field_next").fetchSemanticsNodes().any { node ->
                node.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isNotBlank()
            }
        }
        compose.onNodeWithTag("details_field_amount").performScrollTo().performTextReplacement("599,00")
        compose.onNodeWithText("Нет, оставить текущую плановую цену").performScrollTo().performClick()
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("details_field_paid").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("499,00 RUB").assertExists()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Подтверждённых платежей нет").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Подтверждённых платежей нет").assertDoesNotExist()
    }
    @Test fun changedActualAmountCanUpdateFuturePrice() {
        createAndOpen()
        compose.onNodeWithText("Подтвердить оплату").performScrollTo().performClick()
        compose.onNodeWithTag("details_field_amount").performTextReplacement("599,00")
        compose.onNodeWithText("Да, использовать новую сумму").performScrollTo().performClick()
        confirm()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("details_field_paid").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("599,00 RUB").fetchSemanticsNodes().isNotEmpty() }
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
    @Test fun doubleConfirmationCreatesOnePayment() {
        createAndOpen()
        compose.onNodeWithText("Подтвердить оплату").performScrollTo().performClick()
        compose.onNodeWithText("Подтвердить").performTouchInput { doubleClick() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("details_field_paid").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5000) { compose.onAllNodes(hasText("Оплата ",substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Оплата ",substring = true)).assertCountEquals(1)
    }
}
