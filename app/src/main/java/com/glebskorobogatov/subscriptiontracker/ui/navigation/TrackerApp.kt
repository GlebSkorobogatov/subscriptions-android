package com.glebskorobogatov.subscriptiontracker.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import com.glebskorobogatov.subscriptiontracker.ui.finance.FinanceScreen
import com.glebskorobogatov.subscriptiontracker.ui.archive.ArchiveScreen
import com.glebskorobogatov.subscriptiontracker.ui.settings.SettingsScreen
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

// Keep serializer names/arguments stable, including the old Finance and Archive routes.
@Serializable data object Subscriptions
@Serializable data object Finance
@Serializable data object Archive
@Serializable data class SubscriptionEditor(val subscriptionId: String? = null)
@Serializable data class SubscriptionDetails(val subscriptionId: String)
@Serializable data object Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerApp(state: SubscriptionsUiState,onRetry: () -> Unit,owner: SubscriptionsViewModel,
    settingsModel: com.glebskorobogatov.subscriptiontracker.ui.settings.SettingsViewModel,
    notifications: com.glebskorobogatov.subscriptiontracker.notifications.NotificationViewModel? = null,
    onNotificationSettings: () -> Unit = {},animationsEnabled: Boolean = true,navController: androidx.navigation.NavHostController? = null) {
    if(state is SubscriptionsUiState.StorageBlocked) { StorageBlockedScreen(state.failure,onRetry); return }
    val nav = navController ?: rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val editor = destination?.hasRoute<SubscriptionEditor>() == true
    val details = destination?.hasRoute<SubscriptionDetails>() == true
    val archive = destination?.hasRoute<Archive>() == true
    val root = when {
        destination?.hasRoute<Finance>() == true -> TrackerTab.Finance
        destination?.hasRoute<Settings>() == true -> TrackerTab.Settings
        destination == null || destination.hasRoute<Subscriptions>() -> TrackerTab.Subscriptions
        else -> null
    }
    var exitRequest by remember { mutableIntStateOf(0) }
    val notificationState = notifications?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(notificationState?.navigation?.sequence,editor) {
        notificationState?.navigation?.let { request ->
            // The existing editor's explicit discard/keep path owns leaving a dirty draft.
            if(editor) { exitRequest++; return@let }
            notifications?.consumed(request.sequence)
            val target = try {
                request.subscription?.takeIf { request.generation == owner.activeDatasetId() && owner.details(it).first() != null }
            } catch(cancel: CancellationException) { throw cancel }
              catch(_: Exception) { null }
            val same = target != null && nav.currentBackStackEntry?.destination?.hasRoute<SubscriptionDetails>() == true &&
                nav.currentBackStackEntry?.toRoute<SubscriptionDetails>()?.subscriptionId == target.toString()
            if(!same) {
                if(target == null) nav.navigate(Subscriptions) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true
                } else nav.navigate(SubscriptionDetails(target.toString())) { launchSingleTop = true }
            }
        }
    }
    // A pre-R4a Archive root may restore with Subscriptions as its parent. Rebuild only that legacy
    // Archive entry with Settings as parent; editor/detail entries are never discarded by this repair.
    LaunchedEffect(entry?.id) {
        if(archive && nav.previousBackStackEntry?.destination?.hasRoute<Settings>() != true) {
            nav.navigate(Settings) { popUpTo<Archive> { inclusive = true }; launchSingleTop = true }
            nav.navigate(Archive) { launchSingleTop = true }
        }
    }
    SideEffect { notifications?.visibleSubscription(if(details) entry?.toRoute<SubscriptionDetails>()?.subscriptionId?.let(UUID::fromString) else null) }
    fun select(tab: TrackerTab) {
        if(tab == root) return
        val route: Any = when(tab) { TrackerTab.Subscriptions -> Subscriptions; TrackerTab.Finance -> Finance; TrackerTab.Settings -> Settings }
        nav.navigate(route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
    }
    val duration = trackerMotionDuration(animationsEnabled)
    CompositionLocalProvider(LocalSubscriptionAssets provides owner) {
        Scaffold(contentWindowInsets = if(root != null) WindowInsets(0,0,0,0) else WindowInsets.safeDrawing,
            topBar = {
                if(editor) com.glebskorobogatov.subscriptiontracker.ui.editor.EditorHeader(stringResource(
                    if(entry?.toRoute<SubscriptionEditor>()?.subscriptionId != null) R.string.editor_edit_title else R.string.editor_title)) { exitRequest++ }
                else if(archive) TrackerPageHeader(stringResource(R.string.archive),onBack = { nav.popBackStack() })
            }) { padding ->
            TrackerRootChrome(root,::select,{ nav.navigate(SubscriptionEditor()) { launchSingleTop = true } },
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),animationsEnabled = animationsEnabled) {
                NavHost(nav,startDestination = Subscriptions,modifier = Modifier.fillMaxSize(),
                    enterTransition = { fadeIn(tween(duration)) },exitTransition = { fadeOut(tween(duration)) }) {
                    composable<Subscriptions> { SubscriptionsScreen(state,
                        onAdd = { nav.navigate(SubscriptionEditor()) { launchSingleTop = true } },
                        onOpen = { nav.navigate(SubscriptionDetails(it.toString())) { launchSingleTop = true } },onRetry = onRetry) }
                    composable<SubscriptionEditor> { backEntry ->
                        val id = backEntry.toRoute<SubscriptionEditor>().subscriptionId?.let(UUID::fromString)
                        val model: com.glebskorobogatov.subscriptiontracker.ui.editor.EditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                            viewModelStoreOwner = backEntry,factory = viewModelFactory { initializer {
                                com.glebskorobogatov.subscriptiontracker.ui.editor.EditorViewModel(createSavedStateHandle(),owner,id)
                            } })
                        com.glebskorobogatov.subscriptiontracker.ui.editor.EditorScreen(model,exitRequest,
                            onSaved = { notifications?.afterSaved(it) },
                            onExitCancelled = { notificationState?.navigation?.let { notifications?.consumed(it.sequence) } }) { nav.popBackStack() }
                    }
                    composable<SubscriptionDetails> { backEntry ->
                        val id = UUID.fromString(backEntry.toRoute<SubscriptionDetails>().subscriptionId)
                        val model: com.glebskorobogatov.subscriptiontracker.ui.details.DetailsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                            viewModelStoreOwner = backEntry,factory = viewModelFactory { initializer {
                                com.glebskorobogatov.subscriptiontracker.ui.details.DetailsViewModel(createSavedStateHandle(),owner,id)
                            } })
                        com.glebskorobogatov.subscriptiontracker.ui.details.DetailsScreen(model,id,0,
                            onBack = { nav.popBackStack() },onEdit = { nav.navigate(SubscriptionEditor(id.toString())) },
                            onDeleted = { if(!nav.popBackStack()) nav.navigate(Subscriptions) { launchSingleTop = true } })
                    }
                    composable<Finance> { backEntry ->
                        val model: com.glebskorobogatov.subscriptiontracker.ui.finance.FinanceViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                            viewModelStoreOwner = backEntry,factory = viewModelFactory { initializer {
                                com.glebskorobogatov.subscriptiontracker.ui.finance.FinanceViewModel(createSavedStateHandle(),owner)
                            } })
                        FinanceScreen(model)
                    }
                    composable<Archive> { backEntry ->
                        val model: com.glebskorobogatov.subscriptiontracker.ui.archive.ArchiveViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                            viewModelStoreOwner = backEntry,factory = viewModelFactory { initializer {
                                com.glebskorobogatov.subscriptiontracker.ui.archive.ArchiveViewModel(owner)
                            } })
                        ArchiveScreen(model) { nav.navigate(SubscriptionDetails(it.toString())) { launchSingleTop = true } }
                    }
                    composable<Settings> { SettingsScreen(settingsModel,notificationState?.access,onNotificationSettings,
                        onArchive = { nav.navigate(Archive) { launchSingleTop = true } }) }
                }
                // A root Back returns to the library, including pre-R4a stacks where Settings
                // used to be pushed above another root. Leaf Back remains owned by its screen.
                BackHandler(enabled = root != null && root != TrackerTab.Subscriptions) { select(TrackerTab.Subscriptions) }
            }
        }
    }
}
