package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import org.junit.Rule
import org.junit.Test

class EditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<IsolatedMainActivity>()
    @Test fun newSubscriptionOffersIconsAndPhotoPickerWithoutGalleryPermission() {
        open()
        compose.onNodeWithText("Изменить значок").assertIsDisplayed().performClick()
        compose.onNodeWithTag("catalog_pick_image").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Закрыть каталог").performClick()
        compose.onNodeWithText("Убрать оформление").assertDoesNotExist()
    }
    private fun open() {
        compose.waitUntil(5000) { compose.onAllNodesWithText("Добавить подписку").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Добавить подписку").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Добавление подписки").assertIsDisplayed()
    }
    private fun save() { compose.onNodeWithText("Сохранить").performScrollToIfScrollable().performClick() }
    @Test fun createAndOpenEditPreservingUuid() {
        open()
        compose.onNodeWithTag("field_name").performTextInput("Тестовый сервис")
        compose.onNodeWithTag("field_amount").performTextInput("499,00")
        save()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("Тестовый сервис"))
        compose.onNodeWithText("Тестовый сервис").performClick()
        compose.onNodeWithText("Изменить").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Редактирование подписки").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Редактирование подписки").assertIsDisplayed()
        compose.onNodeWithTag("field_name").assertTextContains("Тестовый сервис")
        compose.onNodeWithTag("field_name").performTextReplacement("Новое название")
        save()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Новое название").assertExists()
    }
    @Test fun repeatedSaveDoesNotCreateTwoCards() {
        open()
        compose.onNodeWithTag("field_name").performTextInput("Одна запись")
        compose.onNodeWithTag("field_amount").performTextInput("100")
        compose.onNodeWithText("Сохранить").performScrollToIfScrollable().performTouchInput { doubleClick() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("Одна запись"))
        compose.onAllNodesWithText("Одна запись").assertCountEquals(1)
    }
    @Test fun emptyNameAndMoneyKeepEditorOpen() {
        open(); save()
        compose.onNodeWithTag("subscription_editor").assertExists()
        compose.onNodeWithTag("field_name").performScrollToIfScrollable().assertExists()
    }
    @Test fun dirtyFormWarnsOnBackAndCanContinue() {
        open(); compose.onNodeWithTag("field_name").performTextInput("Черновик")
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Есть несохранённые изменения").assertIsDisplayed()
        compose.onNodeWithText("Продолжить редактирование").performClick()
        compose.onNodeWithTag("field_name").assertTextContains("Черновик")
    }
    @Test fun rotationPreservesDraft() {
        open(); compose.onNodeWithTag("field_name").performTextInput("Черновик")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("field_name").assertTextContains("Черновик")
    }
    @Test fun catalogSelectionSurvivesRotationAndSavesWithForm() {
        open()
        compose.onNodeWithTag("editor_choose_catalog").performClick()
        compose.onNodeWithTag("catalog_generic-ai").assertExists().performClick()
        compose.onNodeWithTag("field_name").assertTextContains("")
        compose.onNodeWithTag("field_name").performTextInput("ИИ-помощник")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("field_name").assertTextContains("ИИ-помощник")
        compose.onNodeWithTag("field_amount").performTextInput("499")
        save()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("ИИ-помощник"))
        compose.onNodeWithText("ИИ-помощник").performClick()
        compose.onNodeWithText("Изменить").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("editor_choose_catalog").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor_choose_catalog").performClick()
        compose.onNodeWithTag("catalog_generic-ai").assertIsSelected()
    }

    @Test fun dismissingCatalogKeepsUnsavedFormUnchanged() {
        open()
        compose.onNodeWithTag("editor_choose_catalog").performClick()
        compose.onNodeWithTag("catalog_grid").assertExists()
        compose.onNodeWithText("Закрыть каталог").performClick()
        compose.onNodeWithTag("field_name").assertTextContains("")
        compose.onNodeWithText("Убрать оформление").assertDoesNotExist()
    }
}
