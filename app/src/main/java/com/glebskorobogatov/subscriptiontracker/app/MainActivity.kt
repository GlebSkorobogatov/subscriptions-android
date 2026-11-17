package com.glebskorobogatov.subscriptiontracker.app

import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.Manifest
import android.provider.Settings as AndroidSettings
import androidx.activity.result.contract.ActivityResultContracts
import com.glebskorobogatov.subscriptiontracker.notifications.*
import android.view.ViewTreeObserver
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.ui.settings.*
import com.glebskorobogatov.subscriptiontracker.ui.design.resolveDarkTheme
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsViewModel
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsSource
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.navigation.TrackerApp

open class MainActivity : ComponentActivity() {
    private val calendarChanges = CalendarRefreshReceiver { subscriptions.refreshToday() }
    private val subscriptions: SubscriptionsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SubscriptionsViewModel(createSubscriptionsSource()) as T
        }
    }

    private val settings: SettingsViewModel by viewModels {
        viewModelFactory { initializer { SettingsViewModel(createSavedStateHandle(),subscriptions) } }
    }
    private val notifications: NotificationViewModel by viewModels {
        viewModelFactory { initializer { NotificationViewModel(createSavedStateHandle(), subscriptions, createNotificationEnvironment()) } }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notifications.refresh() }
    protected open fun createNotificationEnvironment(): NotificationEnvironment = AndroidNotificationEnvironment(applicationContext)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == NotificationRoute.OPEN) notifications.open(intent.dataString)
    }
    private fun openNotificationSettings() {
        startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName))
    }
    private var firstDraw: ViewTreeObserver.OnPreDrawListener? = null
    override fun onDestroy() {
        firstDraw?.let { window.decorView.viewTreeObserver.removeOnPreDrawListener(it) }
        firstDraw = null
        super.onDestroy()
    }
    protected open fun createSubscriptionsSource(): SubscriptionsSource {
        val appContext = applicationContext
        return SubscriptionsComposition(appContext,
            reconcile = { com.glebskorobogatov.subscriptiontracker.background.WorkManagerReminderScheduler(appContext).requestReconcile() })
    }

    override fun onResume() {
        super.onResume()
        subscriptions.refreshToday()
        notifications.refresh()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, calendarChanges, CalendarRefreshReceiver.filter(), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStop() {
        unregisterReceiver(calendarChanges)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.glebskorobogatov.subscriptiontracker.ui.navigation.RestoredNavigation.sanitize(savedInstanceState)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent?.action == NotificationRoute.OPEN) notifications.open(intent.dataString)
        firstDraw = ViewTreeObserver.OnPreDrawListener {
            val value = settings.state.value
            val ready = value.data != null || value.phase == SettingsPhase.Error || subscriptions.state.value is com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.StorageBlocked
            if(ready) { firstDraw?.let { window.decorView.viewTreeObserver.removeOnPreDrawListener(it) }; firstDraw = null }
            ready
        }.also { window.decorView.viewTreeObserver.addOnPreDrawListener(it) }
        setContent {
            val state by subscriptions.state.collectAsStateWithLifecycle()
            val preferences by settings.state.collectAsStateWithLifecycle()
            val notificationState by notifications.state.collectAsStateWithLifecycle()
            val theme = preferences.data?.theme ?: Theme.SYSTEM
            val dark = resolveDarkTheme(theme,isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window,window.decorView).apply {
                    isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                }
            }
            TrackerTheme(theme) {
                if (state is com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.StorageBlocked)
                    com.glebskorobogatov.subscriptiontracker.ui.subscriptions.StorageBlockedScreen(
                        (state as com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.StorageBlocked).failure, subscriptions::retry)
                else if(preferences.data == null) Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if(preferences.phase == SettingsPhase.Loading) CircularProgressIndicator()
                    else {
                        Text(stringResource(R.string.settings_load_error))
                        Button(onClick = settings::retryRead) { Text(stringResource(R.string.retry_loading)) }
                    }
                } else TrackerApp(state, subscriptions::retry, subscriptions,settings,notifications,::openNotificationSettings)
                if (state !is com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.StorageBlocked && notificationState.explanation) AlertDialog(
                    onDismissRequest = notifications::dismissExplanation,
                    title = { Text(stringResource(R.string.notifications_explanation_title)) },
                    text = { Text(stringResource(R.string.notifications_explanation), Modifier.verticalScroll(rememberScrollState())) },
                    confirmButton = { TextButton(onClick = {
                        if (notifications.confirmExplanation() && android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text(stringResource(R.string.notifications_continue)) } },
                    dismissButton = { TextButton(onClick = notifications::dismissExplanation) { Text(stringResource(R.string.notifications_later)) } })
            }
        }
    }
}

/** Foreground dates change without an Activity resume at midnight or after a clock/zone change.
 * Reuse the existing session refresh: no second calendar, database reopen or persisted cycle. */
internal class CalendarRefreshReceiver(private val refresh: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in actions) refresh()
    }
    companion object {
        private val actions = setOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
        fun filter() = IntentFilter().apply { actions.forEach(::addAction) }
    }
}
