package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.util.UUID

@Composable
fun SubscriptionsScreen(state: SubscriptionsUiState,onAdd: () -> Unit,onOpen: (UUID) -> Unit,onRetry: () -> Unit,
    listState: LazyListState = rememberLazyListState()) {
    BindRootList(TrackerTab.Subscriptions,listState,canHide = state is SubscriptionsUiState.Content)
    val chrome = LocalRootContentPadding.current
    val horizontal = trackerPageHorizontalPadding()
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        .testTag("subscriptions_screen"),state = listState,
        contentPadding = PaddingValues(start = horizontal,end = horizontal,
            top = chrome.calculateTopPadding()+12.dp,bottom = chrome.calculateBottomPadding()+12.dp)) {
        when(state) {
            SubscriptionsUiState.Loading -> item("loading") { TrackerLoading(stringResource(R.string.subscriptions_loading)) }
            is SubscriptionsUiState.StorageBlocked -> item("storage") { StorageBlockedScreen(state.failure,onRetry) }
            SubscriptionsUiState.Error -> item("error") { LoadError(onRetry) }
            is SubscriptionsUiState.Empty -> item("empty") {
                BoxWithConstraints(Modifier.fillMaxWidth().fillParentMaxHeight()) {
                    val safe = (maxHeight - chrome.calculateTopPadding() - chrome.calculateBottomPadding() - 24.dp).coerceAtLeast(TrackerDimensions.minimumTouchTarget)
                    Column(Modifier.fillMaxWidth().heightIn(max = safe).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.List,null,Modifier.size(80.dp),tint = TrackerDesign.colors.textSecondary)
                        Text(stringResource(R.string.subscriptions_empty),Modifier.fillMaxWidth().semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,textAlign = TextAlign.Center)
                        Text(stringResource(if(state.names.isEmpty()) R.string.subscriptions_empty_explanation else R.string.subscriptions_only_archive),
                            color = TrackerDesign.colors.textSecondary,textAlign = TextAlign.Center)
                        TrackerPrimaryButton(stringResource(R.string.add_first_subscription),onAdd,Modifier.fillMaxWidth())
                    }
                }
            }
            is SubscriptionsUiState.Content -> items(state.active+state.cancelled,key = { it.id.toString() }) { SubscriptionRow(it,onOpen) }
        }
    }
}
@Composable internal fun SectionTitle(text: String,modifier: Modifier = Modifier) {
    Text(text,style = MaterialTheme.typography.titleMedium,modifier = modifier.semantics { heading() })
}
@Composable internal fun LoadError(onRetry: () -> Unit) {
    TrackerError(stringResource(R.string.subscriptions_error)+" "+stringResource(R.string.subscriptions_error_explanation),
        stringResource(R.string.retry_loading),onRetry,Modifier.fillMaxWidth().padding(vertical = 24.dp))
}
