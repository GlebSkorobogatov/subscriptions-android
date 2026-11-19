package com.glebskorobogatov.subscriptiontracker.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.SavedStateHandle
import com.glebskorobogatov.subscriptiontracker.ui.settings.SettingsViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.navigation.TrackerApp
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class SubscriptionsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = LocalDate.of(2027, 1, 31)
    private val now = Instant.parse("2027-01-31T12:00:00Z")
    private val dataset = UUID.randomUUID()
    private lateinit var db: TrackerDatabase
    private lateinit var lease: DatasetSettingsFactory.Lease
    private lateinit var use: SubscriptionUseCases
    private lateinit var vm: SubscriptionsViewModel
    private lateinit var settingsVm: SettingsViewModel
    private val store = ViewModelStore()

    @Before fun open() = runBlocking {
        com.glebskorobogatov.subscriptiontracker.fixtures.authorizeFreshFixture(context, dataset); db = TrackerDatabaseFactory.open(context, dataset)
        lease = DatasetSettingsFactory.acquire(context, dataset)
        use = SubscriptionUseCases(db, lease.repository, CurrentDate(day))
    }
    @After fun close() = runBlocking {
        val readers = if (::vm.isInitialized) vm.viewModelScope.coroutineContext.job else null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        withTimeout(5000) { readers?.join() }
        db.close(); lease.close()
        context.deleteDatabase("subscriptions-$dataset.db").also { _ -> com.glebskorobogatov.subscriptiontracker.fixtures.forgetFixtureInitialization(context, "subscriptions-$dataset.db") }
        context.preferencesFile().delete()
        Unit
    }
    private fun Context.preferencesFile() = java.io.File(filesDir, "datastore/settings-$dataset.preferences_pb")
    private fun start(
        fontScale: Float = 1f,
        restoration: StateRestorationTester? = null,
        beforeWrite: suspend () -> Unit = {},
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = SubscriptionsViewModel(object : SubscriptionsSource {
                override fun settings() = use.settings
                override fun finance() = use.forecasts
                override fun today() = use.date.today.value
                override fun details(id: UUID) = use.details(id)
                override fun observe(refreshes: Flow<Unit>) = subscriptionStates(use)
                override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T =
                    withContext(Dispatchers.IO) {
                        beforeWrite()
                        block(use)
                    }
            })
            store.put("screen", vm)
            settingsVm = SettingsViewModel(SavedStateHandle(),vm); store.put("settings",settingsVm)
        }
        val content: @Composable () -> Unit = {
            val state by vm.state.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                TrackerTheme { TrackerApp(state, vm::retry, vm,settingsVm) }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitUntil(5000) { vm.state.value !is SubscriptionsUiState.Loading }
    }
    private fun create(label: String="Личный",date: LocalDate=day,price: Long=49900) = runBlocking {
        use.createSubscription(SubscriptionDraft(UUID.randomUUID(),"Сервис",label,RubPrice(price),ScheduleEdit(date,Frequency(FrequencyUnit.MONTH,1))),now)
    }
    private fun show(id: UUID) {
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasTestTag("subscription_$id"))
        compose.onNodeWithTag("subscription_$id").assertIsDisplayed()
    }
    private fun awaitContent(count: Int) {
        compose.waitUntil(5000) {
            (vm.state.value as? SubscriptionsUiState.Content)?.let { it.active.size + it.cancelled.size == count } == true
        }
    }

    @Test fun editorFeedbackAppearsAboveIslandOnlyAfterConfirmedCommit() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        start(beforeWrite = {
            entered.complete(Unit)
            release.await()
        })
        compose.onNodeWithText("Добавить подписку").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("field_name").performTextInput("Учебное сохранение R7")
        compose.onNodeWithTag("field_amount").performTextInput("123,45")
        try {
            compose.onNodeWithTag("editor_save").performClick()
            compose.waitUntil(5000) { entered.isCompleted }
            compose.onNodeWithTag("editor_save_feedback").assertDoesNotExist()
            assertTrue(runBlocking(Dispatchers.IO) { db.subscriptions().all().isEmpty() })
            release.complete(Unit)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("editor_save_feedback").fetchSemanticsNodes().isNotEmpty() }
            val feedback = compose.onNodeWithTag("editor_save_feedback").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val island = compose.onNodeWithTag("bottom_navigation").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(feedback.bottom <= island.top)
            val saved = runBlocking(Dispatchers.IO) { db.subscriptions().all().single() }
            assertEquals(12345L, saved.priceMinor)
            assertEquals("Учебное сохранение R7", saved.name)
        } finally {
            release.complete(Unit)
        }
    }

    @Test fun cleanLaunchAndFirstAddNavigateToRealEditor() {
        start()
        compose.onNodeWithText("Подписок пока нет").assertIsDisplayed()
        compose.onNodeWithText("Добавить подписку").performClick()
        compose.onNodeWithTag("subscription_editor").assertIsDisplayed()
        compose.onNodeWithText("Добавление подписки").assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        for (field in listOf("name", "amount", "date")) {
            compose.onNodeWithTag("field_$field").performScrollTo().assertIsDisplayed().assertHasClickAction()
        }
        compose.onNodeWithTag("field_count").assertDoesNotExist()
        compose.onNodeWithTag("field_currency").assertDoesNotExist()
        compose.onNodeWithText("Периодичность оплаты").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("frequency_month").assertIsSelected()
        for (tag in listOf("frequency_week", "frequency_quarter", "frequency_year", "frequency_custom"))
            compose.onNodeWithTag(tag).assertHasClickAction()
        compose.onNodeWithText("Автопродление").assertDoesNotExist()
        compose.onNodeWithText("Ручное продление").assertDoesNotExist()
        compose.onNodeWithText("Сохранить").performScrollToIfScrollable().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("tab_subscriptions").assertIsSelected()
    }
    @Test fun useCaseInsertionIsReactiveAndDuplicateServicesRetainLabels() {
        start(); val a = create(); val b = create("Семейный", day.plusDays(3))
        awaitContent(2)
        show(a.id); compose.onNodeWithText("Личный").assertIsDisplayed()
        show(b.id); compose.onNodeWithText("Семейный").assertIsDisplayed()
        compose.onNodeWithText("Требуют решения").assertDoesNotExist()
        val state = vm.state.value as SubscriptionsUiState.Content
        assertEquals(listOf(a.id, b.id), state.active.map { it.id })
        assertEquals(149700L,runBlocking { use.forecasts.first().forecast!!.next30Days.totalMinor })
        compose.onNodeWithTag("subscriptions_screen").performScrollToIndex(0)
        compose.onNodeWithTag("forecast_summary").assertDoesNotExist()
    }
    @Test fun cancelledRowsAreBelowActiveWithoutDuplicatesOrProblemStates() {
        val cancelled=create("Отменённая")
        val end=day.plusDays(4)
        runBlocking { use.changeLifecycle(cancelled,LifecycleCommand.Cancel(end),now) }
        val active=create("Обычная")
        start(); awaitContent(2)
        val state=vm.state.value as SubscriptionsUiState.Content
        assertEquals(listOf(cancelled.id),state.cancelled.map { it.id }); assertEquals(listOf(active.id),state.active.map { it.id })
        show(cancelled.id); compose.onNodeWithText("Отменена").assertIsDisplayed()
        compose.onAllNodesWithTag("subscription_${cancelled.id}").assertCountEquals(1)
    }
    @Test fun cancelledUnknownAccessNeverShowsImaginedDate() {
        val s = create()
        runBlocking { withContext(Dispatchers.IO) { db.subscriptions().update(s.copy(state=SubscriptionState.CANCELLED)) } }
        start(); show(s.id)
        compose.onNodeWithText("Дата окончания доступа неизвестна").assertIsDisplayed()
        compose.onNodeWithText("Отменена").assertIsDisplayed()
        val state = vm.state.value as SubscriptionsUiState.Content
        assertTrue(state.active.isEmpty())
        assertNull(state.cancelled.single().eventDate)
        assertEquals(0L,runBlocking { use.forecasts.first().forecast!!.next30Days.totalMinor })
        compose.onNodeWithTag("subscriptions_screen").performScrollToIndex(0)
        compose.onNodeWithText("В ближайшие 30 дней ожидаемых списаний нет").assertDoesNotExist()
    }
    @Test fun cardRoutesByUuidAndDeletedTargetShowsNotFound() {
        val a = create(); val b = create("Семейный")
        start(); show(b.id)
        compose.onNodeWithTag("subscription_${b.id}").performClick()
        compose.onNodeWithTag("details_${b.id}").assertIsDisplayed()
        runBlocking { use.deleteSubscription(b, true) }
        compose.waitUntil(5000) { (vm.state.value as? SubscriptionsUiState.Content)?.names?.containsKey(b.id) == false }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Подписка не найдена").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Подписка не найдена").assertIsDisplayed()
        compose.onNodeWithText("Вернуться к подпискам").performClick()
        show(a.id)
    }
    @Test fun onlyArchiveExplainsAbsenceAndKeepsArchiveTab() {
        val s = create()
        runBlocking { use.changeLifecycle(s, LifecycleCommand.Archive, now) }
        start()
        assertTrue(vm.state.value is SubscriptionsUiState.Empty)
        compose.onNodeWithText("Подписок пока нет").assertIsDisplayed()
        compose.openArchive()
        compose.onNodeWithTag("archive_screen").assertExists()
    }
    @Test fun largeFontKeepsPrimaryActionsReachableByScrolling() {
        start(fontScale = 2f)
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("Добавить подписку"))
        compose.onNodeWithText("Добавить подписку").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Назад").assertIsDisplayed().performClick()
        compose.onNodeWithTag("tab_subscriptions").assertIsSelected()
    }
    @Test fun detailsRouteRestoresWithSameUuidAndData() {
        val s = create()
        val restoration = StateRestorationTester(compose)
        start(restoration = restoration); show(s.id)
        compose.onNodeWithTag("subscription_${s.id}").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("details_${s.id}").assertIsDisplayed()
        compose.onNodeWithText("Сервис").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick(); show(s.id)
    }
    @Test fun realForecastOverflowShowsErrorAndRetryPreservesSubscription() {
        val row = create(price = Long.MAX_VALUE)
        start()
        awaitContent(1)
        compose.onNodeWithTag("tab_finance").performClick()
        val failure = context.getString(com.glebskorobogatov.subscriptiontracker.R.string.finance_error)
        compose.waitUntil(5000) { compose.onAllNodesWithText(failure).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Повторить загрузку").performClick()
        assertTrue(vm.state.value is SubscriptionsUiState.Content)
        compose.onNodeWithText("Подписок пока нет").assertDoesNotExist()
        runBlocking { assertEquals(row, use.subscriptions.first().single()) }
    }
    @Test fun loadingAndErrorAreExplicitAndRetryIsClickable() {
        var state by mutableStateOf<SubscriptionsUiState>(SubscriptionsUiState.Loading)
        var retries = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = SubscriptionsViewModel { subscriptionStates(use) }
            store.put("screen", vm)
            settingsVm = SettingsViewModel(SavedStateHandle(),vm); store.put("settings",settingsVm)
        }
        compose.setContent { TrackerTheme { TrackerApp(state, { retries++ }, vm,settingsVm) } }
        compose.onNodeWithText("Загружаем подписки…").assertIsDisplayed()
        compose.onNodeWithText("Подписок пока нет").assertDoesNotExist()
        compose.runOnIdle { state = SubscriptionsUiState.Error }
        compose.onNodeWithText("Подписок пока нет").assertDoesNotExist()
        compose.onNodeWithText("Повторить загрузку").performClick()
        assertEquals(1, retries)
    }

    @Test fun financialOverflowLeavesLibraryDetailsAndEditorAvailableUntilUserRepairsPrice() {
        val row = create(price = Long.MAX_VALUE)
        start(); awaitContent(1)
        compose.onNodeWithTag("forecast_summary").assertDoesNotExist()
        compose.onNodeWithTag("tab_finance").performClick()
        val failure = context.getString(com.glebskorobogatov.subscriptiontracker.R.string.finance_error)
        compose.waitUntil(5000) { compose.onAllNodesWithText(failure).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("finance_total").assertDoesNotExist()
        compose.onNodeWithTag("tab_subscriptions").performClick()
        show(row.id); compose.onNodeWithTag("subscription_${row.id}").performClick()
        compose.onNodeWithTag("details_${row.id}").assertIsDisplayed()
        compose.onNodeWithText(context.getString(com.glebskorobogatov.subscriptiontracker.R.string.details_primary_edit)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_amount").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("field_amount").performScrollTo().performTextClearance()
        compose.onNodeWithTag("field_amount").performTextInput("1,23")
        compose.onNodeWithText("Сохранить").performScrollToIfScrollable().performClick()
        compose.waitUntil(5000) { (vm.state.value as? SubscriptionsUiState.Content)?.active?.singleOrNull()?.price?.amountMinor == 123L }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("subscriptions_screen").performScrollToIndex(0)
        compose.onNodeWithTag("forecast_error",useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("forecast_summary").assertDoesNotExist()
        compose.onNodeWithTag("tab_finance").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("finance_30_total").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("finance_screen").performScrollToNode(hasTestTag("finance_30_total"))
        compose.onNodeWithTag("finance_30_total").assertTextEquals("2,46 ₽")
        val saved = runBlocking { withContext(Dispatchers.IO) { db.subscriptions().find(row.id) } }!!
        assertEquals(123L,saved.priceMinor)
        assertEquals(row.id,saved.id); assertEquals(row.anchorDay,saved.anchorDay)
    }
}
