package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.room.StorageFailure
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerPrimaryButton

/** Replaces the entire navigation tree, including restored routes and notification destinations. */
@Composable
fun StorageBlockedScreen(failure: StorageFailure, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(20.dp).testTag("storage_blocked"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.storage_unavailable_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(when (failure) {
            StorageFailure.INCOMPATIBLE -> R.string.storage_incompatible
            StorageFailure.CORRUPT, StorageFailure.INVALID_POINTER, StorageFailure.LOST_DATABASE, StorageFailure.INITIALIZATION_UNCERTAIN -> R.string.storage_corrupt
            StorageFailure.TEMPORARY -> R.string.storage_temporary
            else -> R.string.storage_unknown
        }), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(stringResource(R.string.storage_manual_cleanup), style = MaterialTheme.typography.bodyMedium)
        if (failure.retryable) TrackerPrimaryButton(stringResource(R.string.retry_loading), retry, Modifier.fillMaxWidth())
    }
}
