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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import com.glebskorobogatov.subscriptiontracker.ui.details.DetailsPhase
import com.glebskorobogatov.subscriptiontracker.ui.details.DetailsScreen
import com.glebskorobogatov.subscriptiontracker.ui.details.DetailsUiState
import com.glebskorobogatov.subscriptiontracker.ui.details.DetailsViewModel
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
    val archive = destination?.hasRoute<Archive>() == true
    val root = when {
        destination?.hasRoute<Finance>() == true -> TrackerTab.Finance
        destination?.hasRoute<Settings>() == true -> TrackerTab.Settings
        destination == null || destination.hasRoute<Subscriptions>() -> TrackerTab.Subscriptions
        else -> null
    }
    var exitRequest by remember { mutableIntStateOf(0) }
    var exitBinding by rememberSaveable(stateSaver = com.glebskorobogatov.subscriptiontracker.ui.editor.ExitBindingSaver) {
        mutableStateOf(com.glebskorobogatov.subscriptiontracker.ui.editor.ExitBinding())
    }
    var userBackRequest by remember { mutableIntStateOf(0) }
    val navigationScope = rememberCoroutineScope()
    val navigationSession = remember { NotificationNavigationSession() }
    val hostLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val notificationState = notifications?.state?.collectAsStateWithLifecycle()?.value
    fun detailsModel(entry: NavBackStackEntry): DetailsViewModel =
        ViewModelProvider.create(entry, viewModelFactory {
            initializer {
                val id = UUID.fromString(entry.toRoute<SubscriptionDetails>().subscriptionId)
                DetailsViewModel(createSavedStateHandle(), owner, id)
            }
        })[DetailsViewModel::class]

    fun leaveDetails(entry: NavBackStackEntry) {
        if (nav.currentBackStackEntry !== entry) return
        if (!nav.popBackStack()) nav.navigate(Subscriptions) { launchSingleTop = true }
    }

    // The request stays pending across a suspended read or write and across Activity recreation.
    // Consuming it after navigation must not cancel the job that applied the decision.
    LaunchedEffect(notificationState?.navigation?.sequence, editor) {
        val model = notifications ?: return@LaunchedEffect
        val request = notificationState?.navigation ?: return@LaunchedEffect
        navigationSession.dispatch(navigationScope, request.sequence, editor) {
            hostLifecycle.awaitAtLeastStarted()
            snapshotFlow { navEntry.value }.first { it != null }
            while (model.state.value.navigation?.sequence == request.sequence) {
                // details() is collected with flowOn(Default). A lost response can resume there.
                // NavController updates the entry lifecycle and must run on the main thread.
                val finished = withContext(Dispatchers.Main.immediate) {
                    val before = nav.currentBackStackEntry
                    val currentDetails = before?.takeIf { it.destination.hasRoute<SubscriptionDetails>() }?.let(::detailsModel)
                    if (currentDetails?.state?.value?.phase == DetailsPhase.OperationInProgress) {
                        currentDetails.state.first { it.phase != DetailsPhase.OperationInProgress }
                        hostLifecycle.awaitAtLeastStarted()
                        return@withContext false
                    }
                    val settled = currentDetails?.state?.value
                    if (before != null && settled?.requiresDetailsExit == true) {
                        leaveDetails(before)
                        return@withContext false
                    }

                    val decision = NotificationNavigationResolver.decide(
                        request = request,
                        editor = before?.destination?.hasRoute<SubscriptionEditor>() == true,
                        visibleDetailsId = { null },
                        activeGeneration = { owner.activeDatasetId() },
                        latestSequence = { model.state.value.navigation?.sequence },
                        exists = { id -> owner.details(id).first() != null },
                    )
                    hostLifecycle.awaitAtLeastStarted()
                    currentCoroutineContext().ensureActive()
                    val after = nav.currentBackStackEntry
                    val editorAfter = after?.destination?.hasRoute<SubscriptionEditor>() == true
                    // An operation may have started or finished while the existence read was suspended.
                    // Resolve again from the current entry once its operation has settled.
                    if (!editorAfter && (before !== after ||
                            currentDetails?.state?.value?.let {
                                it.phase == DetailsPhase.OperationInProgress || it.requiresDetailsExit
                            } == true)) return@withContext false
                    val visible = visibleNotificationTarget(nav)
                    val onMain = when {
                        editorAfter && decision !is NotificationNavigationDecision.Superseded -> NotificationNavigationDecision.AwaitEditor
                        decision is NotificationNavigationDecision.OpenDetails && request.generation != owner.activeDatasetId() ->
                            NotificationNavigationDecision.Home
                        decision is NotificationNavigationDecision.OpenDetails && visible == decision.subscriptionId ->
                            NotificationNavigationDecision.AlreadyVisible
                        else -> decision
                    }
                    applyNotificationNavigation(
                        request = request,
                        decision = onMain,
                        latestSequence = { model.state.value.navigation?.sequence },
                        navigateHome = { openRoot(Subscriptions) },
                        navigateDetails = { id ->
                            val route = SubscriptionDetails(id.toString())
                            if (!nav.popBackStack(route, inclusive = false)) nav.navigate(route)
                        },
                        confirmExit = {
                            exitBinding = exitBinding.confirmExit(request.sequence)
                            exitRequest++
                        },
                        consume = { model.consumed(it) },
                    )
                    true
                }
                if (finished) break
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
    SideEffect {
        if (notifications != null) {
            val detailsEntry = nav.currentBackStackEntry?.takeIf { it.destination.hasRoute<SubscriptionDetails>() }
            if (detailsEntry == null) notifications.visibleSubscription(null)
            else {
                val details = detailsModel(detailsEntry)
                notifications.visibleSubscription(UUID.fromString(detailsEntry.toRoute<SubscriptionDetails>().subscriptionId)) {
                    val current = details.state.value
                    current.phase != DetailsPhase.OperationInProgress && !current.requiresDetailsExit
                }
            }
        }
    }
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
                            liveNotificationSequence = notificationState?.navigation?.sequence,
                            boundExitSequence = exitBinding.sequence,
                            onSaved = { notifications?.afterSaved(it) },
                            onExitArmed = { live -> exitBinding = exitBinding.arm(live) },
                            onExitCancelled = {
                                exitBinding.sequence?.let { notifications?.consumed(it) }
                                exitBinding = exitBinding.finish()
                            },
                            onExitAbandoned = { exitBinding = exitBinding.finish() }) { nav.popBackStack() }
                    }
                    composable<SubscriptionDetails> { backEntry ->
                        val id = UUID.fromString(backEntry.toRoute<SubscriptionDetails>().subscriptionId)
                        DetailsScreen(detailsModel(backEntry), id, 0,
                            onBack = { leaveDetails(backEntry) },
                            onEdit = {
                                if (nav.currentBackStackEntry === backEntry) nav.navigate(SubscriptionEditor(id.toString()))
                            },
                            onDeleted = { leaveDetails(backEntry) },
                            onArchived = { leaveDetails(backEntry) })
                    }
                    composable<Finance> { backEntry ->
                        val model: com.glebskorobogatov.subscriptiontracker.ui.finance.FinanceViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                            viewModelStoreOwner = backEntry,factory = viewModelFactory { initializer {
                                com.glebskorobogatov.subscriptiontracker.ui.finance.FinanceViewModel(createSavedStateHandle(),owner)
                            } })
                        FinanceScreen(model, settingsModel)
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

private val DetailsUiState.requiresDetailsExit: Boolean
    get() = deleted || archived || phase == DetailsPhase.NotFound

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
