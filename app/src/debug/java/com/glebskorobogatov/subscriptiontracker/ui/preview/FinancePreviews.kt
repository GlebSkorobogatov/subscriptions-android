package com.glebskorobogatov.subscriptiontracker.ui.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.finance.*
import java.time.*
import java.util.UUID

internal object FinancePreviewData {
    val day: LocalDate = LocalDate.of(2027,1,31)
    val rows = listOf(
        DetailsEditorPreviewData.row.copy(id = UUID(0,801),name = "Учебный сервис",accountLabel = "Личный",priceMinor = 49900,
            anchorYear = 2027,anchorMonth = 1,anchorDay = 31,currentPeriodStartOverride = null,currentPeriodEnd = null),
        DetailsEditorPreviewData.row.copy(id = UUID(0,802),name = "Учебный сервис",accountLabel = "Семейный",priceMinor = 59900,
            anchorYear = 2027,anchorMonth = 1,anchorDay = 31,currentPeriodStartOverride = null,currentPeriodEnd = null,intervalUnit = com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit.YEAR))
    fun chartState(): FinanceUiState {
        val sample = rows.take(2).map { it.copy(anchorYear = 2026,anchorMonth = 11,anchorDay = 30,intervalCount = 3) }
        val today = LocalDate.of(2026,11,15)
        val result = ForecastEngine().calculate(sample.map { it.toDomain() },today)
        return FinanceUiState(FinancePhase.Content,FinanceSnapshot(result,sample),YearMonth.of(2026,11))
    }
    fun state(): FinanceUiState {
        val result = ForecastEngine().calculate(rows.map { it.toDomain() },day)
        return FinanceUiState(FinancePhase.Content,FinanceSnapshot(result,rows),YearMonth.from(day))
    }
}
@Preview(name = "Прогноз · светлая",showBackground = true,widthDp = 390,heightDp = 800)
@Composable private fun FinanceLight() { TrackerTheme(Theme.LIGHT) { FinancePane(FinancePreviewData.chartState()) } }
@Preview(name = "Прогноз · тёмная",showBackground = true,widthDp = 320,heightDp = 700,uiMode = Configuration.UI_MODE_NIGHT_YES,fontScale = 2f)
@Composable private fun FinanceDark() { TrackerTheme(Theme.DARK) { FinancePane(FinancePreviewData.chartState().copy(month = YearMonth.of(2026,12))) } }

@Preview(name = "Статистика · пустая", showBackground = true, widthDp = 360, heightDp = 800)
@Composable private fun FinanceEmpty() {
    val today = FinancePreviewData.day
    val empty = FinanceSnapshot(ForecastEngine().calculate(emptyList(), today), emptyList())
    TrackerTheme(Theme.LIGHT) { FinancePane(FinanceUiState(FinancePhase.Empty, empty, YearMonth.from(today))) }
}

@Preview(name = "Статистика · ошибка", showBackground = true, widthDp = 320, fontScale = 2f)
@Composable private fun FinanceError() {
    TrackerTheme(Theme.DARK) { FinancePane(FinanceUiState(phase = FinancePhase.Error)) }
}
