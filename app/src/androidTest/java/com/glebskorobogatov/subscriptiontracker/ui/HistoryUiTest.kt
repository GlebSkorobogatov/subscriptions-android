package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class HistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<IsolatedMainActivity>()
    private val name = "История D04"
    private val empty = "История пока пуста. Добавьте ранее совершённый платёж или подтвердите продление в деталях подписки."
    private fun openHistory(manual: Boolean = false) {
        compose.waitUntil(5000) { compose.onAllNodesWithText("Добавить первую подписку").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Добавить первую подписку").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("field_name").performTextInput(name)
        compose.onNodeWithTag("field_amount").performTextInput("499")
        if(manual) {
            compose.onNodeWithText("Ручное продление").performScrollTo().performClick()
            compose.onNodeWithTag("field_date").performScrollTo().performTextReplacement(LocalDate.now().minusDays(2).toString())
        }
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("История платежей и возвратов").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("История платежей и возвратов").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Добавить платёж в историю").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun save() {
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("history_field_date").fetchSemanticsNodes().isEmpty() }
    }
    private fun addPayment(amount: String = "999", currency: String = "RUB") {
        compose.onNodeWithText("Добавить платёж в историю").performClick()
        compose.onNodeWithTag("history_field_amount").performScrollTo().performTextReplacement(amount)
        compose.onNodeWithTag("history_field_currency").performScrollTo().performTextReplacement(currency)
        save()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("history_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_list").performScrollToNode(hasText("Исторический платёж"))
        compose.onNodeWithText("Исторический платёж").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Добавить возврат").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun refund(amount: String) {
        compose.onNodeWithText("Добавить возврат").performScrollTo().performClick()
        compose.onNodeWithTag("history_field_amount").performScrollTo().performTextReplacement(amount)
        save()
    }
    private fun net(amount: String, currency: String = "RUB") {
        val text = "Чистые расходы: $amount $currency"
        compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
    @Test fun emptyHistoryOpensFromDetailsAndReturnsToSameSubscription() {
        openHistory()
        compose.onNodeWithText(empty).assertIsDisplayed()
        compose.onNodeWithTag("tab_subscriptions").assertDoesNotExist()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText(name).assertExists()
        compose.onNodeWithText("499,00 RUB").assertExists()
    }
    @Test fun historicalPaymentDoesNotConfirmOverdueManualRenewalOrChangeFuturePrice() {
        openHistory(manual = true); addPayment(); net("999,00")
        compose.onNodeWithText("Обновить стоимость будущих продлений?").assertDoesNotExist()
        compose.onNodeWithContentDescription("Назад").performClick() // payment -> history
        compose.onNodeWithContentDescription("Назад").performClick() // history -> subscription
        compose.onNodeWithText("499,00 RUB").assertExists()
        compose.onNodeWithText("Ожидает продления").assertExists()
        compose.onNodeWithText("Подтверждённых платежей нет").assertDoesNotExist()
    }
    @Test fun partialThenFullRefundKeepsPaymentAndRejectsExcess() {
        openHistory(); addPayment(); refund("300"); net("699,00")
        refund("200"); net("499,00"); refund("499"); net("0,00")
        compose.onNodeWithText("999,00 RUB").assertExists()
        compose.onNodeWithText("Добавить возврат").performScrollTo().performClick()
        compose.onNodeWithTag("history_field_amount").performScrollTo().performTextReplacement("1")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(hasTestTag("history_field_amount") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("history_error_amount",useUnmergedTree = true).assertTextEquals(
            "Возврат должен быть положительным, в валюте платежа, между датой оплаты и сегодня. Все возвраты вместе не могут превышать платёж.")
        compose.onNodeWithText("Отмена").performClick(); net("0,00")
    }
    @Test fun refundCanBeEditedThenDeletedWithoutDeletingPayment() {
        openHistory(); addPayment(); refund("300"); net("699,00")
        compose.onNodeWithContentDescription("Действия с возвратом 300,00 RUB от ${LocalDate.now()}").performScrollTo().performClick()
        compose.onNodeWithText("Редактировать возврат").performClick()
        compose.onNodeWithTag("history_field_amount").performScrollTo().performTextReplacement("200"); save(); net("799,00")
        compose.onNodeWithContentDescription("Действия с возвратом 200,00 RUB от ${LocalDate.now()}").performScrollTo().performClick()
        compose.onNodeWithText("Удалить возврат").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Удалить").fetchSemanticsNodes().isEmpty() }
        net("999,00"); compose.onNodeWithText("Возвратов пока нет").performScrollTo().assertIsDisplayed()
    }
    @Test fun paymentEditCannotReduceBelowRefundsButAcceptsValidCorrection() {
        openHistory(); addPayment(); refund("300"); net("699,00")
        compose.onNodeWithContentDescription("Действия с платежом").performScrollTo().performClick()
        compose.onNodeWithText("Редактировать платёж").performClick()
        compose.onNodeWithTag("history_field_amount").performScrollTo().performTextReplacement("299")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(hasTestTag("history_field_amount") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("history_error_amount",useUnmergedTree = true).assertTextEquals(
            "Проверьте дату и сумму платежа. Сумма не может быть ниже возвратов, валюта должна быть согласована, а дата оплаты — не позже дат возвратов.")
        compose.onNodeWithTag("history_field_amount").performScrollTo().performTextReplacement("1200"); save(); net("900,00")
        compose.onNodeWithText("1200,00 RUB").assertExists()
    }
    @Test fun cancelledPaymentDeletionKeepsHistoryAndConfirmedDeletionRemovesIt() {
        openHistory(); addPayment(); refund("300"); net("699,00")
        compose.onNodeWithContentDescription("Действия с платежом").performScrollTo().performClick()
        compose.onNodeWithText("Удалить платёж").performClick()
        compose.onNodeWithText("Платёж будет удалён без возможности восстановления. Все связанные с ним возвраты также будут удалены.").assertExists()
        compose.onNodeWithText("Отмена").performClick(); net("699,00")
        compose.onNodeWithContentDescription("Действия с платежом").performScrollTo().performClick()
        compose.onNodeWithText("Удалить платёж").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("К списку платежей").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("Платёж не найден. Возможно, он был удалён.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("К списку платежей").performClick()
        compose.onNodeWithText(empty).assertIsDisplayed()
    }
    @Test fun historicalDraftSurvivesRecreationAndDoubleSaveCreatesOnePayment() {
        openHistory(); compose.onNodeWithText("Добавить платёж в историю").performClick()
        compose.onNodeWithTag("history_field_amount").performScrollTo().performTextReplacement("777,00")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("history_field_amount").assertTextContains("777,00")
        compose.onNodeWithText("Сохранить").performTouchInput { doubleClick() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("history_field_date").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("history_list").performScrollToNode(hasText("Исторический платёж"))
        compose.onAllNodesWithText("Исторический платёж").assertCountEquals(1)
        compose.onNodeWithText("Чистые расходы: 777,00 RUB").assertExists()
    }
    @Test fun refundKeepsHistoricalCurrencyAndDraftAfterRecreation() {
        openHistory(); addPayment("10","USD"); net("10,00","USD")
        compose.onNodeWithText("Добавить возврат").performScrollTo().performClick()
        compose.onNodeWithTag("history_field_amount").performScrollTo().performTextReplacement("3")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("history_field_amount").assertTextContains("3")
        compose.onNodeWithTag("history_field_currency").assertTextContains("USD")
        save(); net("7,00","USD")
    }
}
