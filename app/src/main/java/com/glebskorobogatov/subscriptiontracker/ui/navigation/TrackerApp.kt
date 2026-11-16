package com.glebskorobogatov.subscriptiontracker.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerBottomNavigation
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerDimensions
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTab
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import androidx.navigation.toRoute
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import java.util.UUID
import com.glebskorobogatov.subscriptiontracker.ui.finance.FinanceScreen
import com.glebskorobogatov.subscriptiontracker.ui.archive.ArchiveScreen
import com.glebskorobogatov.subscriptiontracker.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable data object Subscriptions
@Serializable data object Finance
@Serializable data object Archive
@Serializable data class SubscriptionEditor(val subscriptionId: String? = null)
@Serializable data class SubscriptionDetails(val subscriptionId: String)
@Serializable data class PaymentHistory(val subscriptionId: String)
@Serializable data object Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerApp(state: SubscriptionsUiState, onRetry: () -> Unit, owner: SubscriptionsViewModel, settingsModel: com.glebskorobogatov.subscriptiontracker.ui.settings.SettingsViewModel,
    notifications: com.glebskorobogatov.subscriptiontracker.notifications.NotificationViewModel? = null,
    onNotificationSettings: () -> Unit = {}) {
    val nav = rememberNavController()
    val notificationState = notifications?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(notificationState?.navigation?.sequence) {
        notificationState?.navigation?.let { request ->
            notifications?.consumed(request.sequence)
            val current = nav.currentBackStackEntry
            val same = request.subscription != null && current?.destination?.hasRoute<SubscriptionDetails>() == true &&
                current.toRoute<SubscriptionDetails>().subscriptionId == request.subscription.toString()
            if (!same) {
                if (request.subscription == null) nav.navigate(Subscriptions) {
                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }; launchSingleTop = true
                } else nav.navigate(SubscriptionDetails(request.subscription.toString())) { launchSingleTop = true }
            }
        }
    }
    var exitRequest by remember { mutableIntStateOf(0) }
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val settings = destination?.hasRoute<Settings>() == true
    val editor = destination?.hasRoute<SubscriptionEditor>() == true
    val details = destination?.hasRoute<SubscriptionDetails>() == true
    val history = destination?.hasRoute<PaymentHistory>() == true
    SideEffect { notifications?.visibleSubscription(if (details) entry?.toRoute<SubscriptionDetails>()?.subscriptionId?.let(UUID::fromString) else null) }
    val secondary = settings || editor || details || history
    val title = when {
        history -> R.string.history_title
        editor -> if (entry?.toRoute<SubscriptionEditor>()?.subscriptionId != null) R.string.editor_edit_title else R.string.editor_title
        details -> R.string.details_title
        settings -> R.string.settings
        destination?.hasRoute<Finance>() == true -> R.string.finance
        destination?.hasRoute<Archive>() == true -> R.string.archive
        else -> R.string.subscriptions
    }
    fun select(route: Any) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val subscriptionsTab = !secondary && destination?.hasRoute<Finance>() != true && destination?.hasRoute<Archive>() != true
    CompositionLocalProvider(com.glebskorobogatov.subscriptiontracker.ui.design.LocalSubscriptionAssets provides owner) {
    Scaffold(topBar = {
        if (subscriptionsTab) SubscriptionsTopBar(
            onAdd = { nav.navigate(SubscriptionEditor()) { launchSingleTop = true } },
            onSettings = { nav.navigate(Settings) { launchSingleTop = true } },
        ) else if (editor) com.glebskorobogatov.subscriptiontracker.ui.editor.EditorHeader(stringResource(title)) { exitRequest++ }
        else if (destination?.hasRoute<Archive>() == true || destination?.hasRoute<Finance>() == true) com.glebskorobogatov.subscriptiontracker.ui.design.TrackerPageHeader(
            stringResource(title),onSettings = { nav.navigate(Settings) { launchSingleTop = true } })
        else if (settings) com.glebskorobogatov.subscriptiontracker.ui.design.TrackerPageHeader(
            stringResource(R.string.settings),onBack = { nav.popBackStack() })
        else if (!details && !history) TopAppBar(title = { Text(stringResource(title)) }, navigationIcon = {
            if (secondary) IconButton(onClick = { if (editor || details || history) exitRequest++ else nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.navigate_back))
            }
        }, actions = {
            if (!secondary) IconButton(onClick = { nav.navigate(Settings) { launchSingleTop = true } }) {
                Icon(Icons.Default.Settings, stringResource(R.string.open_settings))
            }
        })
    }, bottomBar = {
        if (!secondary) TrackerBottomNavigation(
            selected = when {
                destination?.hasRoute<Finance>() == true -> TrackerTab.Finance
                destination?.hasRoute<Archive>() == true -> TrackerTab.Archive
                else -> TrackerTab.Subscriptions
            },
            onSelect = { tab -> select(when (tab) {
                TrackerTab.Subscriptions -> Subscriptions
                TrackerTab.Finance -> Finance
                TrackerTab.Archive -> Archive
            }) },
        )
    }) { padding ->
        NavHost(navController = nav, startDestination = Subscriptions,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            enterTransition = { fadeIn(tween(TrackerDimensions.navigationAnimationMillis)) },
            exitTransition = { fadeOut(tween(TrackerDimensions.navigationAnimationMillis)) }) {
            composable<Subscriptions> {
                SubscriptionsScreen(state, onAdd = { nav.navigate(SubscriptionEditor()) { launchSingleTop = true } },
                    onOpen = { nav.navigate(SubscriptionDetails(it.toString())) { launchSingleTop = true } }, onRetry = onRetry)
            }
            composable<SubscriptionEditor> { backEntry ->
                val editorId = backEntry.toRoute<SubscriptionEditor>().subscriptionId?.let(UUID::fromString)
                val model: com.glebskorobogatov.subscriptiontracker.ui.editor.EditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    viewModelStoreOwner = backEntry, factory = viewModelFactory {
                        initializer { com.glebskorobogatov.subscriptiontracker.ui.editor.EditorViewModel(createSavedStateHandle(),owner,editorId) }
                    })
                com.glebskorobogatov.subscriptiontracker.ui.editor.EditorScreen(model, exitRequest, onSaved = { notifications?.afterSaved(it) }) { nav.popBackStack() }
            }
            composable<SubscriptionDetails> { backEntry ->
                val id = UUID.fromString(backEntry.toRoute<SubscriptionDetails>().subscriptionId)
                val model: com.glebskorobogatov.subscriptiontracker.ui.details.DetailsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    viewModelStoreOwner = backEntry, factory = viewModelFactory {
                        initializer { com.glebskorobogatov.subscriptiontracker.ui.details.DetailsViewModel(createSavedStateHandle(),owner,id) }
                    })
                com.glebskorobogatov.subscriptiontracker.ui.details.DetailsScreen(model,id,0,
                    onBack = { nav.popBackStack() },onEdit = { nav.navigate(SubscriptionEditor(id.toString())) },
                    onDeleted = { nav.navigate(Subscriptions) { popUpTo(nav.graph.findStartDestination().id) { inclusive = true }; launchSingleTop = true } },
                    onHistory = { nav.navigate(PaymentHistory(id.toString())) { launchSingleTop = true } })
            }
            composable<PaymentHistory> { backEntry ->
                val id = UUID.fromString(backEntry.toRoute<PaymentHistory>().subscriptionId)
                val model: com.glebskorobogatov.subscriptiontracker.ui.history.HistoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                    viewModelStoreOwner = backEntry, factory = viewModelFactory {
                        initializer { com.glebskorobogatov.subscriptiontracker.ui.history.HistoryViewModel(createSavedStateHandle(),owner,id) }
                    })
                com.glebskorobogatov.subscriptiontracker.ui.history.HistoryScreen(model,exitRequest,
                    onBack = { nav.popBackStack() },onMissing = { nav.navigate(Subscriptions) {
                        popUpTo(nav.graph.findStartDestination().id) { inclusive = true }; launchSingleTop = true
                    } })
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
                    viewModelStoreOwner = backEntry,factory = viewModelFactory { initializer { com.glebskorobogatov.subscriptiontracker.ui.archive.ArchiveViewModel(owner) } })
                ArchiveScreen(model) { nav.navigate(SubscriptionDetails(it.toString())) { launchSingleTop = true } }
            }
            composable<Settings> { SettingsScreen(settingsModel, notificationState?.access, onNotificationSettings) }
        }
    }
    }
}
