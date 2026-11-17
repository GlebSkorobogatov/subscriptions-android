package com.glebskorobogatov.subscriptiontracker.ui.design

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic component host only; never opens a dataset or replaces a production source. */
class TrackerComponentsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun themeSwitchKeepsInputAndMatchesSemanticAndMaterialColors() {
        var theme by mutableStateOf(Theme.LIGHT)
        var text by mutableStateOf("")
        compose.setContent { TrackerTheme(theme) {
            assertEquals(TrackerDesign.colors.background,MaterialTheme.colorScheme.background)
            TrackerTextField(text,{ text = it },"Учебное имя",inputModifier = Modifier.testTag("input"))
        } }
        compose.onNodeWithTag("input").performTextInput("Черновик")
        compose.runOnIdle { theme = Theme.DARK }
        compose.onNodeWithTag("theme_dark").assertExists()
        compose.onNodeWithTag("input").assertTextContains("Черновик")
        compose.runOnIdle { theme = Theme.LIGHT }
        compose.onNodeWithTag("theme_light").assertExists()
        compose.onNodeWithTag("input").assertTextContains("Черновик")
    }
    @Test fun systemThemeTracksConfigurationWithoutChangingPreference() {
        var dark by mutableStateOf(false)
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if(dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) { TrackerTheme(Theme.SYSTEM) { Text("Система") } }
        }
        compose.onNodeWithTag("theme_light").assertExists()
        compose.runOnIdle { dark = true }
        compose.onNodeWithTag("theme_dark").assertExists()
    }
    @Test fun primaryAndIconControlsKeepMinimumTouchTargets() {
        compose.setContent { TrackerTheme { Column {
            TrackerPrimaryButton("Действие",{},Modifier.testTag("primary"))
            TrackerIconButton(Icons.Outlined.Close,"Закрыть",{},Modifier.testTag("icon"))
        } } }
        compose.onNodeWithTag("primary").assertHeightIsAtLeast(52.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("icon").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Закрыть").assertExists()
    }
    @Test fun loadingAndDisabledButtonsCannotSubmit() {
        var calls = 0
        compose.setContent { TrackerTheme { Column {
            TrackerPrimaryButton("Сохранить",{ calls++ },Modifier.testTag("pending"),loading = true)
            TrackerSecondaryButton("Недоступно",{ calls++ },Modifier.testTag("disabled"),enabled = false)
        } } }
        compose.onNodeWithTag("pending").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Загрузка…"))
        compose.onNodeWithTag("disabled").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0,calls) }
    }
    @Test fun capsuleSupportsSingleAndMultipleSelectionWithoutColorOnlyState() {
        var selected by mutableStateOf(false)
        var multiple by mutableStateOf(true)
        compose.setContent { TrackerTheme { Column {
            TrackerSelectedCapsule("Месяц",selected,{ selected = !selected },Modifier.testTag("single"))
            TrackerSelectedCapsule("За 3 дня",multiple,{ multiple = !multiple },Modifier.testTag("multiple"),multiple = true)
            TrackerSelectedCapsule("Недоступно",false,{},Modifier.testTag("disabled"),enabled = false)
        } } }
        compose.onNodeWithTag("single").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithTag("single").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Выбрано"))
        compose.onNodeWithTag("multiple").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("disabled").assertIsNotEnabled()
        compose.onNodeWithTag("single").assertHeightIsAtLeast(48.dp)
    }
    @Test fun fieldRetainsErrorSemanticsInputTagImeAndSupportingMessage() {
        compose.setContent { TrackerTheme {
            TrackerTextField("Некорректно",{},"Стоимость",errorMessage = "Проверьте стоимость",
                inputModifier = Modifier.testTag("field_amount"),supportingModifier = Modifier.testTag("support"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
        } }
        compose.onNodeWithTag("field_amount").assertContentDescriptionEquals("Стоимость")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error,"Проверьте стоимость"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction,ImeAction.Done))
        compose.onNodeWithTag("support",useUnmergedTree = true).assertTextEquals("Проверьте стоимость")
    }
    @Test fun retryCallsOnlyRequestedActionAndSuccessHasPoliteFeedback() {
        var retries = 0
        compose.setContent { TrackerTheme { Column {
            TrackerError("Ошибка прогноза","Повторить",{ retries++ })
            TrackerSuccessCapsule("Изменения сохранены",Modifier.testTag("success"))
        } } }
        compose.onNodeWithText("Повторить").performClick()
        compose.runOnIdle { assertEquals(1,retries) }
        compose.onNodeWithTag("success").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,LiveRegionMode.Polite))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
    @Test fun periodEndpointsAreAccessibleAndTrackIsFourDp() {
        compose.setContent { TrackerTheme { Column {
            TrackerPeriodIndicator(0.0,0,SubscriptionState.ACTIVE,"Сегодня",Modifier.testTag("track0"),Modifier.testTag("value0"))
            TrackerPeriodIndicator(1.0,30,SubscriptionState.ACTIVE,"Активна",Modifier.testTag("track1"),Modifier.testTag("value1"))
        } } }
        compose.onNodeWithTag("track0").assertHeightIsEqualTo(4.dp)
        compose.onNodeWithTag("track1").assertHeightIsEqualTo(4.dp)
        compose.onNodeWithTag("value0").assertRangeInfoEquals(ProgressBarRangeInfo(0f,0f..1f))
        compose.onNodeWithTag("value1").assertRangeInfoEquals(ProgressBarRangeInfo(1f,0f..1f))
        compose.onNodeWithTag("value0").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Сегодня. Осталось 0% расчётного периода"))
    }
    @Test fun unknownPeriodHasNoVisualProgressWhileDayTextRemains() {
        compose.setContent { TrackerTheme { Column {
            Text("Осталось 2 дня")
            TrackerPeriodIndicator(null,2,SubscriptionState.ACTIVE,"Неизвестно",Modifier.testTag("unknown"))
            TrackerPeriodIndicator(null,0,SubscriptionState.CANCELLED,"Отменена",Modifier.testTag("cancelled"))
        } } }
        compose.onNodeWithTag("unknown").assertDoesNotExist()
        compose.onNodeWithTag("cancelled").assertDoesNotExist()
        compose.onNodeWithText("Осталось 2 дня").assertExists()
    }
    @Test fun narrowLargeFontMoneyAndButtonsGrowWithoutTextClipping() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                TrackerTheme { Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                    TrackerMoney(Long.MAX_VALUE,Modifier.testTag("money"))
                    TrackerPrimaryButton("Сохранить длинное учебное название",{},Modifier.fillMaxWidth().testTag("save"))
                } }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("money").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertFalse(results.single().hasVisualOverflow)
        compose.onNodeWithText("Сохранить длинное учебное название").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("save").assertHeightIsAtLeast(52.dp)
    }
    @Test fun busyConfirmationRetainsExplicitConsentAndDoesNotInvokeActions() {
        var busy by mutableStateOf(true)
        var confirms = 0
        compose.setContent { TrackerTheme {
            TrackerConfirmationDialog("Удалить учебную запись?","Только выбранная запись.","Удалить","Отмена",{ confirms++ },{},
                destructive = true,busy = busy)
        } }
        compose.onNodeWithText("Удалить").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0,confirms); busy = false }
        compose.onNodeWithText("Удалить").performClick()
        compose.runOnIdle { assertEquals(1,confirms) }
    }
    @Test fun sheetCanScrollToLastActionAtLargeFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                TrackerTheme { TrackerBottomSheet({}) {
                    repeat(40) { Text("Учебная строка $it") }
                    TrackerPrimaryButton("Готово",{},Modifier.fillMaxWidth())
                } }
            }
        }
        compose.onNodeWithText("Готово").performScrollTo().assertIsDisplayed()
    }
}
