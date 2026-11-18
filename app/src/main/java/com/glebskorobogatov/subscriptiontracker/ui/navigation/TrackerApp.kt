package com.glebskorobogatov.subscriptiontracker.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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
    // Home is already the start entry. Restoring it replays the root this pop just saved when that
    // id was not registered yet: a legacy Archive repaired under Settings makes system Back a no-op.
    fun openRoot(route: Any) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = route != Subscriptions
        }
    }
    val navEntry = nav.currentBackStackEntryAsState()
    val entry = navEntry.value
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
    var userBackRequest by remember { mutableIntStateOf(0) }
    val navigationScope = rememberCoroutineScope()
    val navigationSession = remember { NotificationNavigationSession() }
    val hostLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val notificationState = notifications?.state?.collectAsStateWithLifecycle()?.value
    // The read runs outside this effect. Marking the request consumed changes its key, and that must not cancel the decision.
    LaunchedEffect(notificationState?.navigation?.sequence,editor) {
        val model = notifications ?: return@LaunchedEffect
        val request = notificationState?.navigation ?: return@LaunchedEffect
        navigationSession.dispatch(navigationScope, request.sequence, editor) {
            // Restoration can publish the editor after this effect has already started with a null route.
            // Wait for a real destination, then read it again after the suspending lookup.
            val editorNow = withContext(Dispatchers.Main.immediate) {
                // currentBackStackEntry is not snapshot state. Read the Compose State so restoration wakes this wait.
                hostLifecycle.awaitAtLeastStarted()
                snapshotFlow { navEntry.value?.destination }.first { it != null }!!.hasRoute<SubscriptionEditor>()
            }
            val decision = NotificationNavigationResolver.decide(
                request = request,
                editor = editorNow,
                visibleDetailsId = { null },
                activeGeneration = { owner.activeDatasetId() },
                latestSequence = { model.state.value.navigation?.sequence },
                exists = { id -> owner.details(id).first() != null },
            )
            withContext(Dispatchers.Main.immediate) {
                currentCoroutineContext().ensureActive()
                val editorAfter = nav.currentBackStackEntry?.destination?.hasRoute<SubscriptionEditor>() == true
                val visible = visibleNotificationTarget(nav)
                val onMain = when {
                    editorAfter && decision !is NotificationNavigationDecision.Superseded -> NotificationNavigationDecision.AwaitEditor
                    decision is NotificationNavigationDecision.OpenDetails && visible == decision.subscriptionId ->
                        NotificationNavigationDecision.AlreadyVisible
                    else -> decision
                }
                applyNotificationNavigation(
                    request = request,
                    decision = onMain,
                    latestSequence = { model.state.value.navigation?.sequence },
                    navigateHome = { openRoot(Subscriptions) },
                    navigateDetails = { id -> nav.navigate(SubscriptionDetails(id.toString())) { launchSingleTop = true } },
                    confirmExit = { exitRequest++ },
                    consume = { model.consumed(it) },
                )
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
        openRoot(route)
    }
    val duration = trackerMotionDuration(animationsEnabled)
    // A fade still installs an alpha animation; None keeps alpha at 1, so a zero duration has no transition.
    val enter = if(duration == 0) EnterTransition.None else fadeIn(tween(duration))
    val exit = if(duration == 0) ExitTransition.None else fadeOut(tween(duration))
    CompositionLocalProvider(LocalSubscriptionAssets provides owner, LocalTrackerAnimations provides animationsEnabled) {
        Scaffold(contentWindowInsets = if(root != null) WindowInsets(0,0,0,0) else WindowInsets.safeDrawing,
            topBar = {
                if(editor) com.glebskorobogatov.subscriptiontracker.ui.editor.EditorHeader(stringResource(
                    if(entry?.toRoute<SubscriptionEditor>()?.subscriptionId != null) R.string.editor_edit_title else R.string.editor_title)) { userBackRequest++ }
                else if(archive) TrackerPageHeader(stringResource(R.string.archive),onBack = { nav.popBackStack() })
            }) { padding ->
            TrackerRootChrome(root,::select,{ nav.navigate(SubscriptionEditor()) { launchSingleTop = true } },
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),animationsEnabled = animationsEnabled) {
                NavHost(nav,startDestination = Subscriptions,modifier = Modifier.fillMaxSize(),
                    enterTransition = { enter },exitTransition = { exit },
                    popEnterTransition = { enter },popExitTransition = { exit }) {
                    composable<Subscriptions> { SubscriptionsScreen(state,
                        onAdd = { nav.navigate(SubscriptionEditor()) { launchSingleTop = true } },
                        onOpen = { nav.navigate(SubscriptionDetails(it.toString())) { launchSingleTop = true } },onRetry = onRetry) }
                    composable<SubscriptionEditor> { backEntry ->
                        val id = backEntry.toRoute<SubscriptionEditor>().subscriptionId?.let(UUID::fromString)
                        val model: com.glebskorobogatov.subscriptiontracker.ui.editor.EditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                            viewModelStoreOwner = backEntry,factory = viewModelFactory { initializer {
                                com.glebskorobogatov.subscriptiontracker.ui.editor.EditorViewModel(createSavedStateHandle(),owner,id)
                            } })
                        com.glebskorobogatov.subscriptiontracker.ui.editor.EditorScreen(model,exitRequest,userBackRequest,
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

private suspend fun androidx.lifecycle.Lifecycle.awaitAtLeastStarted() {
    if (currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
    suspendCancellableCoroutine { cont ->
        val observer = object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
                if (source.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) && cont.isActive) {
                    source.lifecycle.removeObserver(this)
                    cont.resume(Unit)
                }
            }
        }
        addObserver(observer)
        cont.invokeOnCancellation { removeObserver(observer) }
    }
}

private fun visibleNotificationTarget(nav: androidx.navigation.NavHostController): UUID? {
    val entry = nav.currentBackStackEntry ?: return null
    if(!entry.destination.hasRoute<SubscriptionDetails>()) return null
    return runCatching { UUID.fromString(entry.toRoute<SubscriptionDetails>().subscriptionId) }.getOrNull()
}
