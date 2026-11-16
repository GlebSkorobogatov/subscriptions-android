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
    private fun start(fontScale: Float = 1f, restoration: StateRestorationTester? = null) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = SubscriptionsViewModel(object : SubscriptionsSource {
                override fun settings() = use.settings
                override fun today() = use.date.today.value
                override fun details(id: UUID) = use.details(id)
                override fun observe(refreshes: Flow<Unit>) = subscriptionStates(use)
                override suspend fun <T> perform(block: suspend (SubscriptionUseCases) -> T): T =
                    withContext(Dispatchers.IO) { block(use) }
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
    private fun create(label: String = "Личный", date: LocalDate = day, currency: String = "RUB",
        mode: RenewalMode = RenewalMode.AUTO, price: Long = 49900) = runBlocking {
        if (currency == "RUB") use.createSubscription(SubscriptionDraft(UUID.randomUUID(), "Сервис", label, Money(price, CurrencyCode.of(currency)), mode,
            RenewalSchedule(RenewalAnchor.from(date), Frequency(FrequencyUnit.MONTH, 1))), now)
        else {
            // Synthetic existing legacy record, not an application currency-creation command.
            val row = SubscriptionEntity(UUID.randomUUID(),"Сервис",label,price,CurrencyCode.of(currency),FrequencyUnit.MONTH,1,
                mode,LifecycleState.ACTIVE,date.year,date.monthValue,date.dayOfMonth,0,null,null,null,null,null,null,null,now,now)
            withContext(Dispatchers.IO) { db.subscriptions().insert(row) }; row
        }
    }
    private fun show(id: UUID) {
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasTestTag("subscription_$id"))
        compose.onNodeWithTag("subscription_$id").assertIsDisplayed()
    }
    private fun awaitContent(count: Int) {
        compose.waitUntil(5000) {
            (vm.state.value as? SubscriptionsUiState.Content)?.let { it.active.size + it.needsDecision.size == count } == true
        }
    }

    @Test fun cleanLaunchAndFirstAddNavigateToRealEditor() {
        start()
        compose.onNodeWithText("Подписок пока нет").assertIsDisplayed()
        compose.onNodeWithText("Добавить первую подписку").performClick()
        compose.onNodeWithTag("subscription_editor").assertIsDisplayed()
        compose.onNodeWithText("Добавление подписки").assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("field_name").fetchSemanticsNodes().isNotEmpty() }
        for (field in listOf("name", "amount", "currency", "count", "date")) {
            compose.onNodeWithTag("field_$field").performScrollTo().assertIsDisplayed().assertHasClickAction()
        }
        for (label in listOf("Периодичность оплаты", "Автопродление", "Ручное продление")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHasClickAction()
        }
        compose.onNodeWithText("Сохранить").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("tab_subscriptions").assertIsSelected()
    }
    @Test fun useCaseInsertionIsReactiveAndDuplicateServicesRetainLabels() {
        start(); val a = create(); val b = create("Семейный", day.plusDays(3), "USD")
        awaitContent(2)
        compose.waitUntil(5000) { (vm.state.value as? SubscriptionsUiState.Content)?.forecast?.size == 2 }
        show(a.id); compose.onNodeWithText("Личный").assertIsDisplayed()
        show(b.id); compose.onNodeWithText("Семейный").assertIsDisplayed()
        compose.onNodeWithText("Требуют решения").assertDoesNotExist()
        val state = vm.state.value as SubscriptionsUiState.Content
        assertEquals(listOf(a.id, b.id), state.active.map { it.id })
        assertEquals(listOf("RUB", "USD"), state.forecast.map { it.currency.value })
        compose.onNodeWithTag("subscriptions_screen").performScrollToIndex(0)
        compose.onNodeWithTag("forecast_summary").assertTextContains("998,00 RUB", substring = true)
        compose.onNodeWithTag("forecast_summary").assertTextContains("499,00 USD", substring = true)
    }
    @Test fun decisionsAreSeparateAndNotDuplicatedInActiveList() {
        val issue = create("Проблема")
        runBlocking { withContext(Dispatchers.IO) { db.subscriptions().update(issue.copy(lifecycleState = LifecycleState.PAYMENT_ISSUE)) } }
        val manual = create("Просрочена", day.minusDays(1), mode = RenewalMode.MANUAL)
        val active = create("Обычная")
        start(); awaitContent(3)
        val state = vm.state.value as SubscriptionsUiState.Content
        assertEquals(setOf(issue.id, manual.id), state.needsDecision.map { it.id }.toSet())
        assertEquals(listOf(active.id), state.active.map { it.id })
        show(issue.id); compose.onNodeWithText("Проблема с оплатой").assertIsDisplayed()
        show(manual.id); compose.onNodeWithText("Ожидает продления").assertIsDisplayed()
        compose.onAllNodesWithTag("subscription_${manual.id}").assertCountEquals(1)
    }
    @Test fun cancelledUnknownAccessNeverShowsImaginedDate() {
        val s = create()
        runBlocking { use.changeLifecycle(s, LifecycleCommand.DisableRenewal(null), now) }
        start(); show(s.id)
        compose.onNodeWithText("Дата окончания доступа неизвестна").assertIsDisplayed()
        compose.onNodeWithText("Продление отменено").assertIsDisplayed()
        assertNull((vm.state.value as SubscriptionsUiState.Content).active.single().eventDate)
        compose.onNodeWithTag("subscriptions_screen").performScrollToIndex(0)
        compose.onNodeWithText("В ближайшие 30 дней ожидаемых списаний нет").assertIsDisplayed()
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
        compose.onNodeWithTag("tab_archive").performClick()
        compose.onNodeWithTag("tab_archive").assertIsSelected()
    }
    @Test fun largeFontKeepsPrimaryActionsReachableByScrolling() {
        start(fontScale = 2f)
        compose.onNodeWithTag("subscriptions_screen").performScrollToNode(hasText("Добавить первую подписку"))
        compose.onNodeWithText("Добавить первую подписку").assertIsDisplayed().performClick()
        compose.onNodeWithText("Назад").performScrollTo().assertIsDisplayed().performClick()
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
        compose.onNodeWithText("Повторить загрузку").performClick()
        compose.waitUntil(5000) { vm.state.value == SubscriptionsUiState.Error }
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
}
