package com.glebskorobogatov.subscriptiontracker.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.glebskorobogatov.subscriptiontracker.data.settings.Settings
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.navigation.*
import com.glebskorobogatov.subscriptiontracker.ui.settings.SettingsViewModel
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import kotlinx.coroutines.flow.*
import org.junit.*

/** Presentation-only legacy topology, actual NavHost/SaveableStateRegistry; no database bypass. */
class LegacyNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    @After fun close() { compose.runOnIdle { store.clear() } }
    @Test fun legacyArchiveParentIsRepairedAndNestedArchiveSurvivesStateRestoration() {
        lateinit var owner: SubscriptionsViewModel
        lateinit var settings: SettingsViewModel
        lateinit var nav: NavHostController
        compose.runOnIdle {
            owner = SubscriptionsViewModel(object: SubscriptionsSource {
                override fun observe(refreshes: Flow<Unit>) = flowOf(SubscriptionsUiState.Empty())
                override fun settings() = flowOf(Settings())
                override fun archive() = flowOf(emptyList<com.glebskorobogatov.subscriptiontracker.data.room.SubscriptionEntity>())
            }); store.put("owner",owner)
            settings = SettingsViewModel(SavedStateHandle(),owner); store.put("settings",settings)
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val state by owner.state.collectAsStateWithLifecycle()
            nav = rememberNavController()
            TrackerTheme { TrackerApp(state,owner::retry,owner,settings,navController = nav) }
        }
        compose.onNodeWithTag("tab_subscriptions").assertExists()
        compose.runOnIdle { nav.navigate(Archive) } // Pre-R4a topology: Subscriptions -> Archive, no Settings parent.
        compose.waitUntil(5000) { compose.onAllNodesWithTag("archive_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle(); restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("archive_screen").assertExists()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("tab_subscriptions").assertIsSelected()
    }
}
