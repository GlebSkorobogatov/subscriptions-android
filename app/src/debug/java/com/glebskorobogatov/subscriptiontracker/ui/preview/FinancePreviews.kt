package com.glebskorobogatov.subscriptiontracker.ui.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.glebskorobogatov.subscriptiontracker.data.payment.toDomain
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.RenewalMode
import com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.finance.*
import java.time.*
import java.util.UUID

internal object FinancePreviewData {
    val day: LocalDate = LocalDate.of(2027,1,31)
    val rub = CurrencyCode.of("RUB")
    val rows = listOf(
        DetailsEditorPreviewData.row.copy(id = UUID(0,801),name = "Учебный сервис",accountLabel = "Личный",priceMinor = 49900,
            currencyCode = rub,anchorYear = 2027,anchorMonth = 1,anchorDay = 31,nextCycleIndex = 0,oneOffDueDate = null,overrideCycleIndex = null,currentPeriodStartOverride = null),
        DetailsEditorPreviewData.row.copy(id = UUID(0,802),name = "Учебный сервис",accountLabel = "Семейный",priceMinor = 59900,
            currencyCode = rub,anchorYear = 2027,anchorMonth = 1,anchorDay = 31,nextCycleIndex = 0,oneOffDueDate = null,overrideCycleIndex = null,currentPeriodStartOverride = null,renewalMode = RenewalMode.MANUAL),
        DetailsEditorPreviewData.row.copy(id = UUID(0,803),name = "Другой сервис",accountLabel = null,priceMinor = 12345,
            currencyCode = CurrencyCode.of("KWD"),anchorYear = 2027,anchorMonth = 1,anchorDay = 31,nextCycleIndex = 0,oneOffDueDate = null,overrideCycleIndex = null,currentPeriodStartOverride = null))
    fun chartState(months: Int = 6): FinanceUiState {
        val sample = rows.take(2).map { it.copy(anchorYear = 2026,anchorMonth = 11,anchorDay = 30,intervalCount = 3) }
        val today = LocalDate.of(2026,11,15)
        val result = ForecastEngine().calendarMonths(sample.map { it.toDomain() },today,months,rub)
        return FinanceUiState(FinancePhase.Content,FinanceSnapshot(result,sample,months,rub),rub,YearMonth.of(2026,11))
    }
    fun state(currency: CurrencyCode = rub,months: Int = 3): FinanceUiState {
        val result = ForecastEngine().calendarMonths(rows.map { it.toDomain() },day,months,currency)
        return FinanceUiState(FinancePhase.Content,FinanceSnapshot(result,rows,months,currency),currency,YearMonth.from(day))
    }
}
@Preview(name = "Прогноз · светлая",showBackground = true,widthDp = 390,heightDp = 800)
@Composable private fun FinanceLight() { TrackerTheme(Theme.LIGHT) { FinancePane(FinancePreviewData.chartState()) } }
@Preview(name = "Прогноз · тёмная",showBackground = true,widthDp = 320,heightDp = 700,uiMode = Configuration.UI_MODE_NIGHT_YES,fontScale = 2f)
@Composable private fun FinanceDark() { TrackerTheme(Theme.DARK) { FinancePane(FinancePreviewData.chartState().copy(month = YearMonth.of(2026,12))) } }
