package com.glebskorobogatov.subscriptiontracker.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.settings.Settings
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState
import com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode
import com.glebskorobogatov.subscriptiontracker.ui.archive.*
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import com.glebskorobogatov.subscriptiontracker.ui.settings.*
import java.time.LocalDate
import java.util.UUID

/** Fictional debug-only renderer data: no Room/DataStore or production dataset access. */
internal object ArchiveSettingsPreviewData {
    val personal = DetailsEditorPreviewData.row.copy(id = UUID(0,61),state = SubscriptionState.ARCHIVED,
        accountLabel = "Личный",archivedAt = LocalDate.of(2027,2,28))
    val family = personal.copy(id = UUID(0,62),accountLabel = "Семейный",archivedAt = null,priceMinor = 1299)
    val archive = ArchiveUiState(ArchivePhase.Content,listOf(personal,family))
    val settings = SettingsUiState(SettingsPhase.Content,Settings())
}

@Composable internal fun ArchiveDemo(state: ArchiveUiState = ArchiveSettingsPreviewData.archive,theme: Theme = Theme.SYSTEM,
    onOpen: (UUID) -> Unit = {},onRetry: () -> Unit = {},onBack: () -> Unit = {}) {
    TrackerTheme(theme) {
        Scaffold(topBar = { TrackerPageHeader(stringResource(R.string.archive),onBack = onBack) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { ArchivePane(state,onOpen,onRetry) }
        }
    }
}
@Composable internal fun SettingsDemo(state: SettingsUiState = ArchiveSettingsPreviewData.settings,theme: Theme = Theme.SYSTEM,
    onTheme: (Theme) -> Unit = {},onOffset: (Int,Boolean) -> Unit = {_,_ ->},
    onRetryRead: () -> Unit = {},onRetryWrite: () -> Unit = {},onBack: () -> Unit = {}) {
    TrackerTheme(theme) {
        TrackerRootChrome(TrackerTab.Settings,{ if(it != TrackerTab.Settings) onBack() },{}) {
            SettingsPane(state,"0.1",onTheme,onOffset,onRetryRead,onRetryWrite)
        }
    }
}

@Preview(name = "Архив • светлая",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Архив • тёмная",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Архив • шрифт ×2",widthDp = 320,heightDp = 720,fontScale = 2f)
@Composable private fun ArchivePreview() = ArchiveDemo()
@Preview(name = "Пустой архив • светлая",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Пустой архив • тёмная",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun EmptyArchivePreview() = ArchiveDemo(ArchiveUiState(ArchivePhase.Empty))
@Preview(name = "Настройки • светлая",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Настройки • тёмная",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Настройки • шрифт ×2",widthDp = 320,heightDp = 720,fontScale = 2f)
@Composable private fun SettingsPreview() = SettingsDemo()
