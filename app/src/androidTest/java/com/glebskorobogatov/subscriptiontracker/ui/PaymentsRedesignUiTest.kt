package com.glebskorobogatov.subscriptiontracker.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.model.Money
import com.glebskorobogatov.subscriptiontracker.ui.details.*
import com.glebskorobogatov.subscriptiontracker.ui.history.*
import com.glebskorobogatov.subscriptiontracker.ui.preview.*
import java.io.File
import org.junit.*
import org.junit.Assert.*

/** Read-only fixtures: production commands and persistence remain exercised by the existing integration suite. */
class PaymentsRedesignUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val data = PaymentsPreviewData
    @Before fun prepare() { compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() } }
    @Test fun paymentClearlySeparatesPlanFromActualAndPreservesPriceChoice() {
        val state = mutableStateOf(data.payment)
        compose.setContent { PaymentDemo(state.value,onChange = { key,value -> state.value = state.value.copy(fields = state.value.fields + (key to value)) }) }
        compose.onNodeWithText("Плановая сумма: 799,00 RUB").assertExists()
        compose.onNodeWithContentDescription("Фактически оплачено").assertExists()
        compose.onNodeWithTag("details_field_amount").assertTextContains("999,00")
        compose.onNodeWithText("Нет, оставить текущую плановую цену").performScrollTo().assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals("keep",state.value.fields["priceChoice"]) }
        compose.onNodeWithText("Да, использовать новую сумму").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("update",state.value.fields["priceChoice"]) }
        compose.onNodeWithTag("details_field_next").performScrollTo().assertTextContains("2027-04-16")
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
    }
    @Test fun equalPriceDoesNotAskToChangeFutureTariff() {
        compose.setContent { PaymentDemo(data.payment.copy(fields = data.payment.fields + ("amount" to "799,00"))) }
        compose.onNodeWithText("Обновить стоимость будущих продлений?").assertDoesNotExist()
    }
    @Test fun automaticPaymentDoesNotExposeSelectedNext() {
        compose.setContent { PaymentDemo(data.payment.copy(data = data.payment.data!!.copy(subscription = DetailsEditorPreviewData.row),fields = data.payment.fields + ("needsNext" to "false"))) }
        compose.onNodeWithTag("details_field_next").assertDoesNotExist()
        compose.onNodeWithText("Предложить дату от фактической оплаты").assertDoesNotExist()
    }
    @Test fun paymentInProgressBlocksSubmitDismissAndChanges() {
        compose.setContent { PaymentDemo(data.payment.copy(phase = DetailsPhase.OperationInProgress,frozen = true)) }
        compose.onNodeWithText("Повторить ту же операцию").assertIsNotEnabled()
        compose.onNodeWithText("Отмена").assertIsNotEnabled()
        compose.onNodeWithTag("details_field_amount").assertIsNotEnabled().assertTextContains("999,00")
    }
    @Test fun uncertainOutcomeKeepsDraftAndOffersSameOperationRetry() {
        var retries = 0
        compose.setContent { RefundDemo(data.refundForm.copy(frozen = true,error = R.string.history_retry_same),onSubmit = { retries++ }) }
        compose.onNodeWithTag("history_field_amount").assertIsNotEnabled().assertTextContains("499,00")
        compose.onNodeWithText("Отмена").assertIsNotEnabled()
        compose.onNodeWithText("Повторить ту же операцию").performClick()
        compose.runOnIdle { assertEquals(1,retries) }
    }
    @Test fun mixedCurrencySummaryIsSeparateFromForecastAndSortedRows() {
        compose.setContent { HistoryDemo() }
        val summary = compose.onNodeWithTag("history_summary")
        summary.assert(hasAnyDescendant(hasText("499,00 RUB")))
        summary.assert(hasAnyDescendant(hasText("15,00 USD")))
        summary.assert(hasAnyDescendant(hasText("1200 JPY")))
        compose.onNodeWithText("Прогноз на 30 дней").assertDoesNotExist()
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("history_payment_${data.rub.id}"))
        compose.onNodeWithTag("history_payment_${data.rub.id}").assertHasClickAction()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
    }
    @Test fun fullRefundRetainsOriginalAndDisplaysZeroNet() {
        val full = data.rubItem.copy(netExpense = Money(0,data.rub.currencyCode),refunds = listOf(data.refund.copy(amountMinor = 99900)))
        compose.setContent { HistoryDemo(data.history.copy(selected = data.rub.id,data = data.history.data!!.copy(history = listOf(full)))) }
        compose.onNodeWithText("999,00 RUB").assertExists()
        compose.onNodeWithText("Полностью возвращён").assertExists()
        compose.onNodeWithText("Чистые расходы: 0,00 RUB").assertExists()
        compose.onNodeWithText("Уже возвращено: 999,00 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Доступно для возврата: 0,00 RUB").performScrollTo().assertIsDisplayed()
    }
    @Test fun contextualPaymentMenuDispatchesEditAndDeleteWithoutExecutingConfirmation() {
        val actions = mutableListOf<HistoryAction>()
        compose.setContent { HistoryDemo(data.history.copy(selected = data.rub.id),onAction = { action,_,_ -> actions += action }) }
        compose.onNodeWithText("Удалить платёж").assertDoesNotExist()
        compose.onNodeWithContentDescription("Действия с платежом").performScrollTo().performClick()
        compose.onNodeWithText("Редактировать платёж").performClick()
        compose.onNodeWithContentDescription("Действия с платежом").performScrollTo().performClick()
        compose.onNodeWithText("Удалить платёж").performClick()
        compose.runOnIdle { assertEquals(listOf(HistoryAction.EditPayment,HistoryAction.DeletePayment),actions) }
    }
    @Test fun refundShowsOriginalReturnedAvailableAndErrorNextToAmount() {
        compose.setContent { RefundDemo(data.refundForm.copy(errors = mapOf("amount" to R.string.history_refund_rules))) }
        compose.onNodeWithText("Исходный платёж: 999,00 RUB").assertExists()
        compose.onNodeWithText("Уже возвращено: 500,00 RUB").assertExists()
        compose.onNodeWithTag("history_field_amount").performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onAllNodesWithText("Доступно для возврата: 499,00 RUB").onLast().performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history_field_currency").performScrollTo().assertTextContains("RUB")
    }
    @Test fun conflictDoesNotOfferFalseSuccessAndCanReload() {
        var reloaded = 0
        compose.setContent { RefundDemo(data.refundForm.copy(conflicted = true),onRefresh = { reloaded++ }) }
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNodeWithTag("history_field_amount").assertIsNotEnabled()
        compose.onNodeWithText("Перезагрузить данные").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,reloaded) }
    }
    @Test fun historyLoadingEmptyErrorMissingAndDeletedPaymentAreSafe() {
        val state = mutableStateOf(HistoryUiState())
        var retries = 0
        compose.setContent { HistoryDemo(state.value,onRefresh = { retries++ }) }
        compose.onNodeWithTag("history_add_payment").assertDoesNotExist()
        compose.runOnIdle { state.value = data.history.copy(phase = HistoryPhase.Empty,data = data.history.data!!.copy(history = emptyList())) }
        compose.onNodeWithText("История пока пуста. Добавьте ранее совершённый платёж или подтвердите продление в деталях подписки.").assertExists()
        compose.runOnIdle { state.value = state.value.copy(phase = HistoryPhase.Error,error = R.string.history_load_error) }
        compose.onNodeWithText("Повторить загрузку").performClick()
        compose.runOnIdle { assertEquals(1,retries);state.value = state.value.copy(phase = HistoryPhase.NotFound) }
        compose.onNodeWithText("Подписка не найдена").assertExists()
        compose.runOnIdle { state.value = data.history.copy(selected = java.util.UUID(0,999)) }
        compose.onNodeWithText("Платёж не найден. Возможно, он был удалён.").assertExists()
        compose.onNodeWithText("К списку платежей").assertHasClickAction()
    }
    @Test fun largeFontRefundIsScrollableAndSaveRemainsAccessible() {
        var saved = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) { RefundDemo(onSubmit = { saved++ }) }
        }
        compose.onNodeWithTag("history_field_amount").performScrollTo().assertIsDisplayed().performTextReplacement("400")
        compose.onNodeWithTag("history_field_currency").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сохранить").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1,saved) }
    }
    @Test fun largeFontPaymentKeepsManualNextDateAndPriceDecisionReachable() {
        val state = mutableStateOf(data.payment)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                PaymentDemo(state.value,onChange = { key,value -> state.value = state.value.copy(fields = state.value.fields + (key to value)) })
            }
        }
        compose.onNodeWithTag("details_field_next").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Да, использовать новую сумму").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("update",state.value.fields["priceChoice"]) }
        compose.onNodeWithText("Подтвердить").assertIsDisplayed().assertHasClickAction()
    }
    @Test fun overflowingSummaryShowsErrorAndRetainsPaymentRows() {
        val one = data.rubItem.copy(payment = data.rub.copy(amountMinor = Long.MAX_VALUE),refunds = emptyList(),netExpense = Money(Long.MAX_VALUE,data.rub.currencyCode))
        val two = data.rubItem.copy(payment = data.rub.copy(id = java.util.UUID(0,99),amountMinor = 1),refunds = emptyList(),netExpense = Money(1,data.rub.currencyCode))
        compose.setContent { HistoryDemo(data.history.copy(data = data.history.data!!.copy(history = listOf(one,two)))) }
        compose.onNodeWithText("Не удалось отобразить итог: сумма превышает допустимый размер. Отдельные операции доступны ниже.").assertExists()
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("history_payment_${data.rub.id}"))
        compose.onNodeWithTag("history_payment_${data.rub.id}").assertHasClickAction()
    }
    @Test fun captureLightPaymentHistoryDetailsAndRefund() = capture(Theme.LIGHT,"light")
    @Test fun captureDarkPaymentHistoryDetailsAndRefund() = capture(Theme.DARK,"dark")
    private fun capture(theme: Theme,name: String) {
        val mode = mutableIntStateOf(0)
        compose.setContent { when(mode.intValue) {
            0 -> HistoryDemo(theme = theme)
            1 -> HistoryDemo(data.history.copy(selected = data.rub.id),theme)
            2 -> PaymentDemo(theme = theme)
            else -> RefundDemo(theme = theme)
        } }
        compose.onNodeWithTag("history_summary").assertExists(); screenshot("history-$name")
        compose.runOnIdle { mode.intValue = 1 }
        compose.onNodeWithTag("history_payment_details").assertExists(); screenshot("payment-$name")
        compose.runOnIdle { mode.intValue = 2 }
        compose.onNodeWithTag("details_field_amount").assertExists(); screenshot("confirmation-$name","details_dialog")
        compose.runOnIdle { mode.intValue = 3 }
        compose.onNodeWithTag("history_field_amount").assertExists(); screenshot("refund-$name","history_dialog")
    }
    private fun screenshot(name: String,tag: String? = null) {
        val directory = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val file = File(directory,"ui04-$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { (if(tag == null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        assertTrue(file.length() > 0)
    }
}
