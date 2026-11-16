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
        val row = data.row.copy(lifecycleState = LifecycleState.CANCELLATION_SCHEDULED,paidAccessEndDate = java.time.LocalDate.of(2027,3,31))
        val detail = data.details.data!!.copy(subscription = row,lifecycle = LifecycleView(VisibleStatus.CANCELLATION_SCHEDULED,null,0.5))
        compose.setContent { DetailsDemo(data.details.copy(data = detail)) }
        compose.onAllNodesWithText("Доступ до 2027-03-31 включительно",useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Доступ до 2027-03-31 включительно").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
    }
    @Test fun unknownAccessDoesNotInventDateOrProgress() {
        val detail = data.details.data!!.copy(subscription = data.row.copy(lifecycleState = LifecycleState.CANCELLATION_SCHEDULED),
            lifecycle = LifecycleView(VisibleStatus.CANCELLATION_SCHEDULED,null,null))
        compose.setContent { DetailsDemo(data.details.copy(data = detail),Theme.DARK) }
        compose.onNodeWithText("Дата окончания доступа неизвестна").performScrollTo().assertIsDisplayed()
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
        for ((label,action) in listOf("Продление отключено" to DetailsAction.Disable,
            "Архивировать" to DetailsAction.Archive,"Удалить подписку" to DetailsAction.Delete)) {
            compose.onNodeWithText(label).performScrollTo().assertHasClickAction().performClick()
            compose.runOnIdle { assertEquals(action,actions.last()) }
        }
    }
    @Test fun legacyIssueHasOnlyNonFinancialRecoveryAndLegacyModesRemainRenderable() {
        val state = mutableStateOf(data.details.copy(data = data.details.data!!.copy(
            subscription = data.row.copy(lifecycleState = LifecycleState.PAYMENT_ISSUE),
            lifecycle = LifecycleView(VisibleStatus.PAYMENT_ISSUE,null,null))))
        val actions = mutableListOf<DetailsAction>()
        compose.setContent { DetailsDemo(state.value,onAction = { actions += it }) }
        compose.openDetailsManagement()
        compose.onNodeWithText("Разрешить проблему изменением даты").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(DetailsAction.ResolveDate),actions) }
        compose.onNodeWithText("Подтвердить оплату").assertDoesNotExist()
        compose.onNodeWithText("История платежей и возвратов").assertDoesNotExist()
        for (mode in RenewalMode.entries) {
            compose.runOnIdle { state.value = state.value.copy(data = state.value.data!!.copy(
                subscription = data.row.copy(renewalMode = mode),lifecycle = LifecycleView(VisibleStatus.ACTIVE,null,null))) }
            compose.onNodeWithText("Разрешить проблему изменением даты").assertDoesNotExist()
            compose.onNodeWithText("Проблема с оплатой").assertDoesNotExist()
            compose.onNodeWithText("Изменить").assertHasClickAction()
        }
    }
    @Test fun archiveKeepsReactivationAndHidesPayment() {
        val detail = data.details.data!!.copy(subscription = data.row.copy(lifecycleState = LifecycleState.ARCHIVED),
            lifecycle = LifecycleView(VisibleStatus.ARCHIVED,null,null))
        compose.setContent { DetailsDemo(data.details.copy(data = detail)) }
        compose.openDetailsManagement()
        compose.onNodeWithText("Возобновить подписку").performScrollTo().assertHasClickAction()
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
        compose.onNodeWithText("Повторить загрузку").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,retries); state.value = state.value.copy(phase = DetailsPhase.NotFound) }
        compose.onNodeWithText("Подписка не найдена").assertIsDisplayed()
        compose.onNodeWithText("Вернуться к подпискам").performScrollTo().assertHasClickAction()
    }
    @Test fun accountLabelVisibleAndReminderOverridesStayIndependent() {
        val state = mutableStateOf(data.editor)
        compose.setContent { EditorDemo(state.value,onChange = { key,value -> state.value = state.value.copy(fields = state.value.fields + (key to value)) }) }
        compose.onNodeWithTag("field_label").performScrollTo().assertIsDisplayed().assertTextContains("Семейный")
        compose.onNodeWithTag("field_anchorDay").assertDoesNotExist()
        compose.onNodeWithTag("editor_extra").performScrollTo().performClick()
        compose.onNodeWithTag("field_anchorDay").performScrollTo().assertTextContains("31")
        compose.onNodeWithTag("editor_inherit_PAYMENT").assertDoesNotExist()
        compose.onNodeWithTag("field_currency").assertDoesNotExist()
        compose.onNodeWithTag("field_lead").assertDoesNotExist()
    }
    @Test fun futureSeriesChoiceAndRequiredConfirmationRemainExplicit() {
        val state = mutableStateOf(data.editor)
        compose.setContent { EditorDemo(state.value,onChange = { key,value -> state.value = state.value.copy(fields = state.value.fields + (key to value)) }) }
        compose.onNodeWithText("Изменить весь будущий график").performScrollTo().assertIsToggleable().assertIsOff().performClick()
        compose.runOnIdle { assertEquals("true",state.value.fields["series"]) }
        compose.onNodeWithText("Изменить весь будущий график").assertIsOn()
        compose.runOnIdle { state.value = state.value.copy(fields = state.value.fields + ("series" to "false"),errors = mapOf("series" to R.string.editor_series_required)) }
        compose.onNodeWithText("Подтвердите изменение всего будущего графика.").performScrollTo().assertIsDisplayed()
    }
    @Test fun fieldErrorsAndSavingPreserveValuesAndDisableSubmission() {
        val state = mutableStateOf(data.editor.copy(errors = mapOf("name" to R.string.editor_invalid)))
        compose.setContent { EditorDemo(state.value) }
        compose.onNodeWithTag("field_name").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)).assertTextContains("Видеосервис")
        compose.runOnIdle { state.value = state.value.copy(saving = true) }
        compose.onNodeWithTag("field_name").assertIsNotEnabled().assertTextContains("Видеосервис")
        compose.onNodeWithText("Сохраняем…").performScrollTo().assertIsNotEnabled()
    }
    @Test fun largeFontInputAndSaveRemainReachable() {
        val state = mutableStateOf(data.editor)
        var saved = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)) {
            EditorDemo(state.value,Theme.DARK,onChange = { key,value -> state.value = state.value.copy(fields = state.value.fields + (key to value)) },onSave = { saved++ })
        } }
        compose.onNodeWithText("Редактирование подписки").assertIsDisplayed()
        compose.onNodeWithTag("field_name").performScrollTo().performClick()
        compose.onNodeWithTag("field_name").performTextReplacement("Длинное название семейной подписки")
        compose.onNodeWithTag("field_name").assertIsFocused().assertTextContains("Длинное название семейной подписки")
        compose.onNodeWithText("Сохранить").performScrollTo().assertIsDisplayed().performClick()
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
}
