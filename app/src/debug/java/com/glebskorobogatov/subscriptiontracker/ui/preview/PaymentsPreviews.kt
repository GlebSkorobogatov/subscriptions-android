package com.glebskorobogatov.subscriptiontracker.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.glebskorobogatov.subscriptiontracker.data.integration.PaymentHistoryItem
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.PaymentSource
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.details.*
import com.glebskorobogatov.subscriptiontracker.ui.history.*
import java.time.*
import java.util.UUID

/** Fictional renderer fixtures. No production database or payment commands are involved. */
internal object PaymentsPreviewData {
    private fun payment(id: Long,currency: String,minor: Long,day: Int) = PaymentEntity(UUID(0,id),DetailsEditorPreviewData.id,UUID(1,id),
        LocalDate.of(2027,3,day),minor,CurrencyCode.of(currency),Instant.EPOCH,PaymentSource.MANUAL_HISTORY)
    val rub = payment(41,"RUB",99900,1)
    val refund = RefundEntity(UUID(0,51),rub.id,LocalDate.of(2027,3,2),30000,rub.currencyCode)
    val secondRefund = refund.copy(id = UUID(0,52),amountMinor = 20000,refundedOn = LocalDate.of(2027,3,3))
    val rubItem = PaymentHistoryItem(rub,listOf(refund,secondRefund),Money(49900,rub.currencyCode))
    private val usd = payment(42,"USD",1500,4)
    private val jpy = payment(43,"JPY",1200,5)
    val history = HistoryUiState(HistoryPhase.Content,DetailsEditorPreviewData.details.data!!.copy(history = listOf(rubItem,
        PaymentHistoryItem(usd,emptyList(),Money(1500,usd.currencyCode)),PaymentHistoryItem(jpy,emptyList(),Money(1200,jpy.currencyCode)))))
    val payment = DetailsEditorPreviewData.details.copy(data = DetailsEditorPreviewData.details.data!!.copy(
        subscription = DetailsEditorPreviewData.row.copy(renewalMode = com.glebskorobogatov.subscriptiontracker.domain.lifecycle.RenewalMode.MANUAL)),action = DetailsAction.Payment,
        fields = mapOf("paid" to "2027-03-16","amount" to "999,00","currency" to "RUB","needsNext" to "true",
            "next" to "2027-04-16","priceChoice" to "","reanchor" to "false"))
    val refundForm = history.copy(selected = rub.id,action = HistoryAction.AddRefund,
        fields = mapOf("date" to "2027-03-16","amount" to "499,00","currency" to "RUB"))
}

@Composable internal fun HistoryDemo(state: HistoryUiState = PaymentsPreviewData.history,theme: Theme = Theme.SYSTEM,
    onBack: () -> Unit = {},onMissing: () -> Unit = {},onRefresh: () -> Unit = {},onSelect: (UUID?) -> Unit = {},
    onAction: (HistoryAction,UUID?,UUID?) -> Unit = {_,_,_ ->}) {
    TrackerTheme(theme) { Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        HistoryPane(state,onBack,onMissing,onRefresh,onSelect,onAction)
    } } }
}
@Composable internal fun PaymentDemo(state: DetailsUiState = PaymentsPreviewData.payment,theme: Theme = Theme.SYSTEM,
    onChange: (String,String) -> Unit = {_,_ ->},onSubmit: () -> Unit = {},onDismiss: () -> Unit = {},onRefresh: () -> Unit = {},onSuggest: () -> Unit = {}) {
    TrackerTheme(theme) { DetailsActionDialog(state,onChange,onSubmit,onDismiss,onRefresh,onSuggest) }
}
@Composable internal fun RefundDemo(state: HistoryUiState = PaymentsPreviewData.refundForm,theme: Theme = Theme.SYSTEM,
    onChange: (String,String) -> Unit = {_,_ ->},onSubmit: () -> Unit = {},onDismiss: () -> Unit = {},onRefresh: () -> Unit = {}) {
    TrackerTheme(theme) { HistoryDialog(state,onChange,onSubmit,onDismiss,onRefresh) }
}

@Preview(name = "История • светлая",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "История • тёмная",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "История • шрифт ×2",widthDp = 320,heightDp = 720,fontScale = 2f)
@Composable private fun HistoryPreview() = HistoryDemo()
@Preview(name = "Платёж • светлая",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Платёж • тёмная",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PaymentDetailsPreview() = HistoryDemo(PaymentsPreviewData.history.copy(selected = PaymentsPreviewData.rub.id))
@Preview(name = "Подтверждение • светлая",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Подтверждение • тёмная",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Подтверждение • шрифт ×2",widthDp = 320,heightDp = 720,fontScale = 2f)
@Composable private fun PaymentPreview() = PaymentDemo()
@Preview(name = "Возврат • светлая",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Возврат • тёмная",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Возврат • шрифт ×2",widthDp = 320,heightDp = 720,fontScale = 2f)
@Composable private fun RefundPreview() = RefundDemo()
