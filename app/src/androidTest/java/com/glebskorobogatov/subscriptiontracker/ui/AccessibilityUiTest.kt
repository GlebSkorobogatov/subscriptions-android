package com.glebskorobogatov.subscriptiontracker.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.archive.archiveRows
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.navigation.TrackerApp
import com.glebskorobogatov.subscriptiontracker.ui.settings.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*

class AccessibilityUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dataset = UUID.randomUUID(); private val store = ViewModelStore()
    private lateinit var db: TrackerDatabase; private lateinit var lease: DatasetSettingsFactory.Lease; private lateinit var use: SubscriptionUseCases
    private lateinit var owner: SubscriptionsViewModel; private lateinit var preferences: SettingsViewModel
    private val day = LocalDate.of(2027,1,31); private val now = Instant.parse("2027-01-31T12:00:00Z")
    private val night = mutableStateOf(false)
    private var archiveOverride: Flow<List<SubscriptionEntity>>? = null
    private val settingsReadFails = MutableStateFlow(false)
    @Before fun open() = runBlocking {
        db = TrackerDatabaseFactory.open(context,dataset); lease = DatasetSettingsFactory.acquire(context,dataset)
        use = SubscriptionUseCases(db,lease.repository,CurrentDate(day))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner = SubscriptionsViewModel(object: SubscriptionsSource {
                override fun today() = day
                override fun observe(refreshes: Flow<Unit>) = subscriptionStates(use)
                override fun archive() = archiveOverride ?: archiveRows(use)
                override fun settings() = combine(use.settings,settingsReadFails) { value,bad -> check(!bad) { "Injected settings read error" }; value }
                override fun details(id: UUID) = use.details(id)
                override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T = block(use)
            }); store.put("owner",owner)
            preferences = SettingsViewModel(SavedStateHandle(),owner); store.put("settings",preferences)
        }
    }
    @After fun close() = runBlocking {
        val job = owner.viewModelScope.coroutineContext[Job]!!
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }; job.join()
        db.close(); lease.close(); context.deleteDatabase("subscriptions-$dataset.db"); File(context.filesDir,"datastore/settings-$dataset.preferences_pb").delete(); Unit
    }
    private fun start(scale: Float = 1f,restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            val state by owner.state.collectAsStateWithLifecycle(); val settings by preferences.state.collectAsStateWithLifecycle()
            val current = LocalConfiguration.current
            val configuration = remember(current,night.value) { Configuration(current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(night.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            } }
            CompositionLocalProvider(LocalConfiguration provides configuration,LocalDensity provides Density(LocalDensity.current.density,scale)) {
                Box(Modifier.width(360.dp).height(480.dp)) { TrackerTheme(settings.data?.theme ?: Theme.SYSTEM) { TrackerApp(state,owner::retry,owner,preferences) } }
            }
        }
        if(restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitUntil(5000) { preferences.state.value.data != null && owner.state.value !is SubscriptionsUiState.Loading }
    }
    private fun settings() { compose.onNodeWithContentDescription("Открыть настройки").performClick() }
    private fun theme(value: String) {
        compose.waitUntil(5000) { preferences.state.value.phase == SettingsPhase.Content && !preferences.state.value.pending }
        compose.onNodeWithTag("settings_theme_$value").performScrollTo().performClick()
        compose.waitUntil(5000) { preferences.state.value.data?.theme?.name == value && preferences.state.value.phase == SettingsPhase.Content }
    }
    private fun awaitStep(stage: String,condition: () -> Boolean) {
        try { compose.waitUntil(5000,condition) }
        catch(e: androidx.compose.ui.test.ComposeTimeoutException) {
            val nodes = compose.onAllNodes(SemanticsMatcher("diagnostic text or tag") {
                it.config.contains(SemanticsProperties.Text) || it.config.contains(SemanticsProperties.EditableText) || it.config.contains(SemanticsProperties.TestTag)
            },useUnmergedTree = true).fetchSemanticsNodes()
            val description = nodes.take(120).joinToString(" | ") { node ->
                val tag = node.config.getOrElse(SemanticsProperties.TestTag) { "" }
                val text = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
                val input = node.config.getOrElse(SemanticsProperties.EditableText) { androidx.compose.ui.text.AnnotatedString("") }.text
                "$tag text=$text input=$input"
            }.replace('\n',' ')
            throw AssertionError("D05 stage=$stage: $description",e)
        }
    }
    @Test fun systemThemeRespondsToConfigurationWhileExplicitChoicesRemainStable() {
        start(); compose.onNodeWithTag("theme_light").assertExists()
        compose.runOnIdle { night.value = true }; compose.onNodeWithTag("theme_dark").assertExists()
        settings(); theme("LIGHT"); compose.onNodeWithTag("theme_light").assertExists()
        compose.runOnIdle { night.value = false }; compose.runOnIdle { night.value = true }
        compose.onNodeWithTag("theme_light").assertExists()
        theme("SYSTEM"); compose.onNodeWithTag("theme_dark").assertExists()
        theme("DARK"); compose.runOnIdle { night.value = false }; compose.onNodeWithTag("theme_dark").assertExists()
    }
    @Test fun enlargedFontKeepsFormsDialogsHistoryArchiveAndReminderActionsReachable() {
        val row = runBlocking { use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Очень длинное учебное название подписки", "Учебный семейный аккаунт",
            Money(999999999,CurrencyCode.of("RUB")),RenewalMode.AUTO,RenewalSchedule(RenewalAnchor.from(day),Frequency(FrequencyUnit.MONTH,1))),now) }
        val archived = runBlocking {
            val s = use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Архивная учебная запись",null,Money(999,CurrencyCode.of("JPY")),RenewalMode.AUTO,
                RenewalSchedule(RenewalAnchor.from(day),Frequency(FrequencyUnit.MONTH,1))),now)
            use.changeLifecycle(s,LifecycleCommand.Archive,now)
        }
        val restoration = StateRestorationTester(compose); start(2f,restoration)
        settings()
        compose.openSettingsOffsets(false)
        compose.onNodeWithTag("settings_cancellation_0").performScrollTo().assertIsDisplayed().assertIsToggleable()
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithTag("settings_time").performScrollTo().performClick()
        compose.onNodeWithText("Применить время").assertIsDisplayed()
        compose.onNodeWithTag("settings_hours").performScrollTo().assertIsDisplayed().performTextReplacement("18")
        compose.onNodeWithTag("settings_minutes").performScrollTo().assertIsDisplayed().performTextReplacement("45")
        compose.onNodeWithText("Применить время").assertIsDisplayed().performClick()
        awaitStep("time_saved_18_45") { compose.onAllNodesWithText("18:45").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasTestTag("subscription_${row.id}"))
        compose.onNodeWithTag("subscription_${row.id}").performClick()
        compose.onNodeWithText("Изменить").assertIsDisplayed().performClick()
        awaitStep("editor_initial_fields") { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("field_name").performScrollTo().performTextReplacement("Изменённое учебное название")
        compose.onNodeWithText("Сохранить").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Продолжить редактирование").assertIsDisplayed().performClick()
        awaitStep("editor_restored_fields") { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("field_name").assertTextContains("Изменённое учебное название")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        awaitStep("editor_save_completed") { compose.onAllNodesWithTag("subscription_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("История платежей и возвратов").performScrollTo().performClick()
        awaitStep("history_loaded") { compose.onAllNodesWithTag("history_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_list").performScrollToNode(hasText("Добавить платёж в историю"))
        compose.onNodeWithText("Добавить платёж в историю").performClick()
        compose.onNodeWithTag("history_field_amount").performScrollTo().performTextReplacement("999")
        compose.onNodeWithText("Сохранить").assertIsDisplayed().performClick()
        awaitStep("history_save_completed") { compose.onAllNodesWithTag("history_field_date").fetchSemanticsNodes().isEmpty() }
        awaitStep("history_payment_visible") {
            try { compose.onNodeWithTag("history_list").performScrollToNode(hasText("Исторический платёж")); true }
            catch(_: AssertionError) { false }
        }
        compose.onNodeWithText("Исторический платёж").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Действия с платежом").performScrollTo().performClick()
        compose.onNodeWithText("Удалить платёж").performClick()
        compose.onNodeWithText("Удалить").assertIsDisplayed(); compose.onNodeWithText("Отмена").performClick()
        repeat(3) { compose.onNodeWithContentDescription("Назад").performClick() }
        compose.onNodeWithTag("tab_archive").performClick()
        awaitStep("archive_loaded") { compose.onAllNodesWithTag("archive_${archived.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("archive_screen").performScrollToNode(hasTestTag("archive_${archived.id}"))
        compose.onNodeWithTag("archive_${archived.id}").assertHasClickAction().assertIsDisplayed()
    }
    @Test fun archiveLoadingAndReadErrorNeverRenderEmptyUntilSuccessfulRetry() {
        val gate = CompletableDeferred<Unit>(); var fail = true
        archiveOverride = flow { gate.await(); check(!fail) { "Injected Room read error" }; emitAll(archiveRows(use)) }
        start(); compose.onNodeWithTag("tab_archive").performClick()
        compose.onNodeWithText("Загружаем архив…").assertExists()
        compose.onNodeWithText("В архиве пока пусто").assertDoesNotExist()
        gate.complete(Unit)
        compose.waitUntil(5000) { compose.onAllNodesWithText("Не удалось загрузить архив. Данные не удалены.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("В архиве пока пусто").assertDoesNotExist()
        fail = false; compose.onNodeWithText("Повторить загрузку").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("В архиве пока пусто").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun settingsReadErrorRetainsThemeAndRetryUsesStoredData() {
        runBlocking { use.updateSettings { it.copy(theme = Theme.DARK) } }; start()
        compose.waitUntil(5000) { preferences.state.value.data?.theme == Theme.DARK }
        settings(); settingsReadFails.value = true
        compose.waitUntil(5000) { preferences.state.value.phase == SettingsPhase.Error }
        compose.onNodeWithTag("theme_dark").assertExists()
        compose.onNodeWithText("Повторить загрузку").assertExists()
        org.junit.Assert.assertEquals(Theme.DARK,runBlocking { use.settings.first().theme })
        settingsReadFails.value = false; compose.onNodeWithText("Повторить загрузку").performClick()
        compose.waitUntil(5000) { preferences.state.value.phase == SettingsPhase.Content }
        compose.onNodeWithTag("settings_theme_DARK").assertIsSelected()
    }

}
