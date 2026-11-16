package com.glebskorobogatov.subscriptiontracker.ui.review

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.archive.*
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import com.glebskorobogatov.subscriptiontracker.ui.details.*
import com.glebskorobogatov.subscriptiontracker.ui.editor.*
import com.glebskorobogatov.subscriptiontracker.ui.finance.*
import com.glebskorobogatov.subscriptiontracker.ui.preview.*
import com.glebskorobogatov.subscriptiontracker.ui.settings.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.LocalDate
import kotlin.math.min

/** Debug-only deterministic states; no database, commands, network or production data access. */
internal enum class ReviewScene {
    Subscriptions, SubscriptionsEmpty, SubscriptionsError, SubscriptionsLong, FinancePlaceholder,
    Details, DetailsCancelled, DetailsLong, EditorCreate, EditorEdit, EditorError,
    Archive, ArchiveEmpty, Settings, TimeDialog, DeleteDialog, UnsavedDialog,
     ResolveDateDialog, DisableDialog, ReactivateDialog, ArchiveDialog,
}
internal object ReviewData {
    const val longName = "Очень длинное учебное название семейной подписки"
    val amounts = listOf(Money(Long.MAX_VALUE,CurrencyCode.of("JPY")),Money(12345,CurrencyCode.of("KWD")),Money(999999999,CurrencyCode.of("RUB")))
    val longRow = SubscriptionsPreviewData.video.copy(name = longName,accountLabel = "Отдельный учебный аккаунт",price = amounts.first())
    val longContent = SubscriptionsPreviewData.content(active = listOf(longRow,
        SubscriptionsPreviewData.family.copy(name = "Учебный сервис KWD",price = amounts[1]),
        SubscriptionsPreviewData.video.copy(id = SubscriptionsPreviewData.issue.id,name = "Учебный сервис RUB",price = amounts[2])),
        decision = emptyList(),forecast = amounts)
    val longDetails = DetailsEditorPreviewData.details.copy(data = DetailsEditorPreviewData.details.data!!.copy(
        subscription = DetailsEditorPreviewData.row.copy(name = longName,priceMinor = Long.MAX_VALUE,currencyCode = CurrencyCode.of("JPY"))))
    val editorError = DetailsEditorPreviewData.editor.copy(fields = DetailsEditorPreviewData.editor.fields + ("name" to longName),errors = mapOf("amount" to R.string.editor_invalid))
    val cancelled = DetailsEditorPreviewData.details.copy(data = DetailsEditorPreviewData.details.data!!.copy(
        subscription = DetailsEditorPreviewData.row.copy(lifecycleState = LifecycleState.CANCELLATION_SCHEDULED,paidAccessEndDate = LocalDate.of(2027,3,31)),
        lifecycle = LifecycleView(VisibleStatus.CANCELLATION_SCHEDULED,null,0.5)))
}

/** Logical viewport simulation fits into the host window; it does not change device settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewViewport(scene: ReviewScene,theme: Theme = Theme.SYSTEM,fontScale: Float = 1f,
    widthDp: Int? = null,heightDp: Int = 844,systemNight: Boolean? = null,onDensity: (Float) -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val original = LocalDensity.current
        val density = if(widthDp == null) original.density else min(
            with(original) { maxWidth.toPx() } / widthDp,with(original) { maxHeight.toPx() } / heightDp)
        val configuration = Configuration(LocalConfiguration.current).apply {
            this.fontScale = fontScale
            if(widthDp != null) { screenWidthDp = widthDp;screenHeightDp = heightDp;orientation = if(widthDp > heightDp) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
            systemNight?.let { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(it) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
        }
        SideEffect { onDensity(density) }
        CompositionLocalProvider(LocalDensity provides Density(density,fontScale),LocalConfiguration provides configuration) {
            val size = if(widthDp == null) Modifier.fillMaxSize() else Modifier.requiredSize(widthDp.dp,heightDp.dp)
            Box(size.testTag("ui06_viewport")) { key(scene,widthDp,heightDp,fontScale) { ReviewContent(scene,theme) } }
        }
    }
}

@Composable private fun ReviewContent(scene: ReviewScene,theme: Theme) {
    when(scene) {
        ReviewScene.Subscriptions -> SubscriptionsShowcase(SubscriptionsPreviewData.content(),theme)
        ReviewScene.SubscriptionsEmpty -> SubscriptionsShowcase(SubscriptionsUiState.Empty(),theme)
        ReviewScene.SubscriptionsError -> SubscriptionsShowcase(SubscriptionsUiState.Error,theme)
        ReviewScene.SubscriptionsLong -> SubscriptionsShowcase(ReviewData.longContent,theme)
        ReviewScene.FinancePlaceholder -> TrackerTheme(theme) { Scaffold(topBar = { TrackerPageHeader(stringResource(R.string.finance),onSettings = {}) },
            bottomBar = { TrackerBottomNavigation(TrackerTab.Finance,{}) }) { padding -> Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { FinancePane(FinanceUiState(phase = FinancePhase.Empty)) } } }
        ReviewScene.Details -> DetailsDemo(theme = theme)
        ReviewScene.DetailsCancelled -> DetailsDemo(ReviewData.cancelled,theme)
        ReviewScene.DetailsLong -> DetailsDemo(ReviewData.longDetails,theme)
        ReviewScene.EditorCreate -> EditorDemo(theme = theme,editing = false)
        ReviewScene.EditorEdit -> EditorDemo(theme = theme)
        ReviewScene.EditorError -> EditorDemo(ReviewData.editorError,theme)
        ReviewScene.Archive -> ArchiveDemo(theme = theme)
        ReviewScene.ArchiveEmpty -> ArchiveDemo(ArchiveUiState(ArchivePhase.Empty),theme)
        ReviewScene.Settings -> SettingsDemo(theme = theme)
        ReviewScene.TimeDialog -> TrackerTheme(theme) { SettingsTimeDialog(ArchiveSettingsPreviewData.settings.copy(timeOpen = true),{_,_ ->},{},{}) }
        ReviewScene.DeleteDialog -> LifecycleReviewDialog(DetailsAction.Delete,theme)
        ReviewScene.ResolveDateDialog -> LifecycleReviewDialog(DetailsAction.ResolveDate,theme)
        ReviewScene.DisableDialog -> LifecycleReviewDialog(DetailsAction.Disable,theme)
        ReviewScene.ReactivateDialog -> LifecycleReviewDialog(DetailsAction.Reactivate,theme)
        ReviewScene.ArchiveDialog -> LifecycleReviewDialog(DetailsAction.Archive,theme)
        ReviewScene.UnsavedDialog -> TrackerTheme(theme) { TrackerConfirmationDialog(stringResource(R.string.editor_unsaved),stringResource(R.string.editor_unsaved_explanation),
            stringResource(R.string.editor_discard),stringResource(R.string.editor_continue),{},{}) }
    }
}

@Composable private fun LifecycleReviewDialog(action: DetailsAction,theme: Theme) {
    val original = DetailsEditorPreviewData.details
    val archived = action == DetailsAction.Reactivate
    val issue = action == DetailsAction.ResolveDate
    val state = original.copy(action = action,data = original.data!!.copy(subscription = original.data.subscription.copy(
        lifecycleState = if(archived) LifecycleState.ARCHIVED else if(issue) LifecycleState.PAYMENT_ISSUE else LifecycleState.ACTIVE),
        lifecycle = if(archived) LifecycleView(VisibleStatus.ARCHIVED,null,null) else if(issue) LifecycleView(VisibleStatus.PAYMENT_ISSUE,null,null) else original.data.lifecycle),
        fields = mapOf("date" to "2027-04-16","amount" to "799,00","currency" to "RUB","count" to "1","unit" to "MONTH","mode" to "AUTO","end" to "2027-03-31","reanchor" to "false"))
    TrackerTheme(theme) { DetailsActionDialog(state,{_,_ ->},{},{},{}) }
}
@Preview(name = "Аудит • настройки 320 ×2",widthDp = 320,heightDp = 720,fontScale = 2f)
@Composable private fun ReviewNarrowPreview() = ReviewViewport(ReviewScene.Settings,fontScale = 2f)
@Preview(name = "Аудит • большие суммы",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun ReviewMoneyPreview() = ReviewViewport(ReviewScene.SubscriptionsLong)
