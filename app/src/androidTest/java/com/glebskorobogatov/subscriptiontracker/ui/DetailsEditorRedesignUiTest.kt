package com.glebskorobogatov.subscriptiontracker.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.ui.details.*
import com.glebskorobogatov.subscriptiontracker.ui.editor.*
import com.glebskorobogatov.subscriptiontracker.ui.preview.*
import java.io.File
import org.junit.*
import org.junit.Assert.*

/** Renderer fixtures only: all real operations remain covered by D-02/D-03 Room/UI regression tests. */
class DetailsEditorRedesignUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val data = DetailsEditorPreviewData
    @Before fun prepare() { compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() } }
    @Test fun cancelledAccessIsRenderedExactlyOnce() {
        val row = data.row.copy(state = SubscriptionState.CANCELLED,cancelledAccessEndExclusive = java.time.LocalDate.of(2027,3,31))
        val detail = data.details.data!!.copy(subscription = row,period = SubscriptionPeriod(java.time.LocalDate.of(2027,3,31),15,0.5))
        compose.setContent { DetailsDemo(data.details.copy(data = detail)) }
        compose.onAllNodesWithText("Доступ до 2027-03-31 (не включая этот день)",useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Доступ до 2027-03-31 (не включая этот день)").performScrollToIfScrollable().assertIsDisplayed()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
    }
    @Test fun unknownAccessDoesNotInventDateOrProgress() {
        val detail = data.details.data!!.copy(subscription = data.row.copy(state = SubscriptionState.CANCELLED),
            period = SubscriptionPeriod(null,null,null))
        compose.setContent { DetailsDemo(data.details.copy(data = detail),Theme.DARK) }
        compose.onNodeWithText("Дата окончания доступа неизвестна").performScrollToIfScrollable().assertIsDisplayed()
        compose.onNodeWithTag("details_progress",useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun activeDetailsShowPlanAndUseSuppliedFraction() {
        compose.setContent { DetailsDemo() }
        compose.onNodeWithText("Плановая стоимость продления").assertExists()
        compose.onNodeWithText("799,00 RUB").assertExists()
        compose.onNodeWithTag("details_progress",useUnmergedTree = true).assertRangeInfoEquals(ProgressBarRangeInfo(0.5f,0f..1f))
        val track = compose.onNodeWithTag("details_progress_track",useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(4f * compose.density.density,track.size.height.toFloat(),1f)
    }
    @Test fun managementIsSeparateAndDispatchesExistingActions() {
        val actions = mutableListOf<DetailsAction>()
        compose.setContent { DetailsDemo(onAction = { actions += it }) }
        compose.onNodeWithText("Удалить подписку").assertDoesNotExist()
        compose.openDetailsManagement()
        for ((label,action) in listOf("Отменить подписку" to DetailsAction.Disable,
            "Архивировать" to DetailsAction.Archive,"Удалить подписку" to DetailsAction.Delete)) {
            compose.onNodeWithText(label).performScrollToIfScrollable().assertHasClickAction().performClick()
            compose.runOnIdle { assertEquals(action,actions.last()) }
        }
    }
    @Test fun cancelledDetailsOfferEndEditAndResumeWithoutLegacyRecovery() {
        val state = data.details.copy(data=data.details.data!!.copy(subscription=data.row.copy(state=SubscriptionState.CANCELLED)))
        val actions=mutableListOf<DetailsAction>()
        compose.setContent { DetailsDemo(state,onAction={ actions+=it }) }
        compose.openDetailsManagement()
        compose.onNodeWithText("Изменить дату окончания доступа").performScrollToIfScrollable().performClick()
        compose.runOnIdle { assertEquals(listOf(DetailsAction.EditEnd),actions) }
        compose.onNodeWithText("Возобновить подписку").performScrollToIfScrollable().assertHasClickAction()
        compose.onNodeWithText("Проблема с оплатой").assertDoesNotExist()
        compose.onNodeWithText("Подтвердить оплату").assertDoesNotExist()
    }
    @Test fun archiveKeepsReactivationAndHidesPayment() {
        val detail = data.details.data!!.copy(subscription = data.row.copy(state = SubscriptionState.ARCHIVED),
            period = SubscriptionPeriod(null,null,null))
        compose.setContent { DetailsDemo(data.details.copy(data = detail)) }
        compose.openDetailsManagement()
        compose.onNodeWithText("Возобновить подписку").performScrollToIfScrollable().assertHasClickAction()
        compose.onNodeWithText("Подтвердить оплату").assertDoesNotExist()
    }
    @Test fun progressOperationBlocksBackEditAndPayment() {
        compose.setContent { DetailsDemo(data.details.copy(phase = DetailsPhase.OperationInProgress)) }
        compose.onNodeWithContentDescription("Назад").assertIsNotEnabled()
        compose.onNodeWithText("Изменить").assertIsNotEnabled()
        compose.onAllNodesWithText("Изменить").assertAll(isNotEnabled())
    }
    @Test fun loadingErrorAndNotFoundRemainDistinctAndRetryWorks() {
        val state = mutableStateOf(data.details.copy(phase = DetailsPhase.Loading,data = null))
        var retries = 0
        compose.setContent { DetailsDemo(state.value,onRefresh = { retries++ }) }
        compose.onNodeWithText("Подписка не найдена").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(phase = DetailsPhase.Error) }
        compose.onNodeWithText("Повторить загрузку").performScrollToIfScrollable().performClick()
        compose.runOnIdle { assertEquals(1,retries); state.value = state.value.copy(phase = DetailsPhase.NotFound) }
        compose.onNodeWithText("Подписка не найдена").assertIsDisplayed()
        compose.onNodeWithText("Вернуться к подпискам").performScrollToIfScrollable().assertHasClickAction()
    }
    @Test fun accountLabelVisibleAndReminderOverridesStayIndependent() {
        val state = mutableStateOf(data.editor)
        compose.setContent { EditorDemo(state.value,onChange = { key,value -> state.value = state.value.copy(fields = state.value.fields + (key to value)) }) }
        compose.onNodeWithTag("field_label").performScrollToIfScrollable().assertIsDisplayed().assertTextContains("Семейный")
        compose.onNodeWithTag("field_anchorDay").assertDoesNotExist()
        compose.onNodeWithTag("editor_extra").performScrollToIfScrollable().performClick()
        compose.onNodeWithTag("field_anchorDay").performScrollToIfScrollable().assertTextContains("31")
        compose.onNodeWithTag("editor_inherit_PAYMENT").assertDoesNotExist()
        compose.onNodeWithTag("field_currency").assertDoesNotExist()
        compose.onNodeWithTag("field_lead").assertDoesNotExist()
    }
    @Test fun dateEditExplainsWholeSeriesAndAnchorValidationRemainsVisible() {
        val state=mutableStateOf(data.editor)
        compose.setContent { EditorDemo(state.value) }
        compose.onNodeWithText("Изменение даты или периодичности обновит всю будущую серию.").performScrollToIfScrollable().assertIsDisplayed()
        compose.onNodeWithText("Изменить весь будущий график").assertDoesNotExist()
        compose.onNodeWithTag("editor_extra").performScrollToIfScrollable().performClick()
        compose.runOnIdle { state.value=state.value.copy(errors=mapOf("anchorDay" to R.string.editor_invalid)) }
        compose.onNodeWithTag("field_anchorDay").performScrollToIfScrollable().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }
    @Test fun fieldErrorsAndSavingPreserveValuesAndDisableSubmission() {
        val state = mutableStateOf(data.editor.copy(errors = mapOf("name" to R.string.editor_invalid)))
        compose.setContent { EditorDemo(state.value) }
        compose.onNodeWithTag("field_name").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)).assertTextContains("Видеосервис")
        compose.runOnIdle { state.value = state.value.copy(saving = true) }
        compose.onNodeWithTag("field_name").assertIsNotEnabled().assertTextContains("Видеосервис")
        compose.onNodeWithText("Сохраняем…").performScrollToIfScrollable().assertIsNotEnabled()
    }
    @Test fun largeFontInputAndSaveRemainReachable() {
        val state = mutableStateOf(data.editor)
        var saved = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)) {
            EditorDemo(state.value,Theme.DARK,onChange = { key,value -> state.value = state.value.copy(fields = state.value.fields + (key to value)) },onSave = { saved++ })
        } }
        compose.onNodeWithText("Редактирование подписки").assertIsDisplayed()
        compose.onNodeWithTag("field_name").performScrollToIfScrollable().performClick()
        compose.onNodeWithTag("field_name").performTextReplacement("Длинное название семейной подписки")
        compose.onNodeWithTag("field_name").assertIsFocused().assertTextContains("Длинное название семейной подписки")
        compose.onNodeWithText("Сохранить").performScrollToIfScrollable().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1,saved) }
    }
    @Test fun captureLightDetailsAndEditor() = capture(Theme.LIGHT,"light")
    @Test fun captureDarkDetailsAndEditor() = capture(Theme.DARK,"dark")
    private fun capture(theme: Theme,name: String) {
        val editor = mutableStateOf(false)
        compose.setContent { if(editor.value) EditorDemo(theme = theme) else DetailsDemo(theme = theme) }
        compose.onNodeWithText("799,00 RUB").assertExists()
        screenshot("details-$name")
        compose.runOnIdle { editor.value = true }
        compose.onNodeWithTag("field_name").assertExists()
        screenshot("editor-$name")
    }
    private fun screenshot(name: String) {
        val directory = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val file = File(directory,"ui03-$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        assertTrue("Screenshot must be stored: ${file.name}",file.length() > 0)
    }
    @Test fun cancelledEditorKeepsNonScheduleFieldsEditableAndFrozenPeriodReadOnly() {
        compose.setContent { EditorDemo(data.editor.copy(scheduleEditable=false)) }
        compose.onNodeWithTag("field_name").assertIsEnabled()
        compose.onNodeWithTag("field_amount").assertIsEnabled()
        compose.onNodeWithTag("frequency_month").assertIsNotEnabled()
        compose.onNodeWithTag("frequency_custom").assertIsNotEnabled()
        compose.onNodeWithTag("field_count").assertDoesNotExist()
        compose.onNodeWithTag("field_date").performScrollToIfScrollable().assertIsNotEnabled()
        compose.onNodeWithTag("date_picker_date").assertIsNotEnabled()
        compose.onNodeWithTag("editor_extra").performScrollToIfScrollable().performClick()
        compose.onNodeWithTag("field_start").performScrollToIfScrollable().assertIsNotEnabled()
        compose.onNodeWithTag("field_anchorDay").assertIsNotEnabled()
    }
}
