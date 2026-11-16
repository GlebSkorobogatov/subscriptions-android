package com.glebskorobogatov.subscriptiontracker.ui.design

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.glebskorobogatov.subscriptiontracker.R

@Preview(name = "UI-01 • светлая", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "UI-01 • тёмная", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "UI-01 • крупный шрифт", widthDp = 320, heightDp = 640, fontScale = 2f)
@Composable
private fun FoundationsPreview() {
    TrackerTheme {
        var selected by remember { mutableStateOf(TrackerTab.Subscriptions) }
        var value by remember { mutableStateOf("") }
        Scaffold(bottomBar = { TrackerBottomNavigation(selected, { selected = it }, windowInsets = WindowInsets(0, 0, 0, 0)) }) { padding ->
            Column(Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
                .padding(TrackerDimensions.screenPadding), verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
                Text(stringResource(R.string.subscriptions), style = MaterialTheme.typography.headlineLarge)
                TrackerSummaryCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.subscriptions_empty), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.subscriptions_empty_explanation))
                }
                TrackerListRow(stringResource(R.string.settings_appearance), supportingText = stringResource(R.string.settings_theme_system))
                HorizontalDivider(color = TrackerDesign.colors.divider)
                TrackerTextField(value, { value = it }, stringResource(R.string.editor_name))
                TrackerPrimaryButton(stringResource(R.string.add_first_subscription), {})
                TrackerSecondaryButton(stringResource(R.string.details_cancel), {})
            }
        }
    }
}

@Preview(name = "Подтверждение • светлая", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Подтверждение • тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConfirmationPreview() {
    TrackerTheme {
        TrackerConfirmationDialog(stringResource(R.string.details_delete), stringResource(R.string.details_delete_warning),
            stringResource(R.string.details_confirm), stringResource(R.string.details_cancel), {}, {}, destructive = true)
    }
}
