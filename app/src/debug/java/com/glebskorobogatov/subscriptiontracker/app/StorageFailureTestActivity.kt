package com.glebskorobogatov.subscriptiontracker.app

import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.Settings
import com.glebskorobogatov.subscriptiontracker.notifications.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/** Synthetic failure host: never resolves a production pointer or opens any user storage. */
class StorageFailureTestActivity : MainActivity() {
    override fun createSubscriptionsSource(): SubscriptionsSource = object : SubscriptionsSource {
        private var attempts = 0
        override fun settings() = flowOf(Settings())
        override fun observe(refreshes: Flow<Unit>) = flow<SubscriptionsUiState> {
            attempts++
            if (intent.getBooleanExtra("transient", false) && attempts > 1) emit(SubscriptionsUiState.Empty())
            else throw StorageUnavailable(if (intent.getBooleanExtra("transient", false)) StorageFailure.TEMPORARY else StorageFailure.INCOMPATIBLE)
        }
    }
    override fun createNotificationEnvironment(): NotificationEnvironment = object : NotificationEnvironment {
        override val runtimePermissionRequired = false
        override fun access() = NotificationAccess(true,true,true)
        override fun generation() = UUID(0, 1)
        override fun claimAutomaticOffer() = false
        override suspend fun reconcile() = Unit
        override fun dismiss(route: NotificationRoute) = Unit
    }
}
