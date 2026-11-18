package com.glebskorobogatov.subscriptiontracker.ui.review

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.data.settings.Settings
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.notifications.NotificationAccess
import com.glebskorobogatov.subscriptiontracker.ui.preview.SettingsDemo
import com.glebskorobogatov.subscriptiontracker.ui.settings.SettingsPhase
import com.glebskorobogatov.subscriptiontracker.ui.settings.SettingsUiState
import kotlin.math.min
import org.junit.Rule
import org.junit.Test

/** Emulator pixels of the settings redesign. Fixtures are synthetic and are not committed. */
class R4bGalleryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun captureSettingsStates() {
        data class Frame(val variant: String,val theme: Theme,val state: SettingsUiState,val width: Int,val height: Int,
            val font: Float,val night: Boolean? = null,val access: NotificationAccess? = null,val openOffsets: Boolean = false,
            val scrollBottom: Boolean = false)
        val disabled = NotificationAccess(runtimeGranted = true,appEnabled = false,renewalsEnabled = true)
        val frames = listOf(
            Frame("light",Theme.LIGHT,SettingsUiState(SettingsPhase.Content,Settings(theme = Theme.LIGHT)),390,844,1f,false),
            Frame("dark",Theme.DARK,SettingsUiState(SettingsPhase.Content,Settings(theme = Theme.DARK)),390,844,1f,true),
            Frame("system",Theme.SYSTEM,SettingsUiState(SettingsPhase.Content,Settings(theme = Theme.SYSTEM)),390,844,1f,true),
            Frame("animations-on",Theme.LIGHT,SettingsUiState(SettingsPhase.Content,Settings(theme = Theme.LIGHT,animationsEnabled = true)),390,844,1f,false),
            Frame("animations-off",Theme.LIGHT,SettingsUiState(SettingsPhase.Content,Settings(theme = Theme.LIGHT,animationsEnabled = false)),390,844,1f,false),
            Frame("offsets",Theme.LIGHT,SettingsUiState(SettingsPhase.Content,Settings(theme = Theme.LIGHT)),390,844,1f,false,openOffsets = true),
            Frame("offsets-empty",Theme.DARK,SettingsUiState(SettingsPhase.Content,Settings(theme = Theme.DARK,renewalOffsets = emptySet())),390,844,1f,true,openOffsets = true),
            Frame("notifications-disabled",Theme.LIGHT,SettingsUiState(SettingsPhase.Content,Settings(theme = Theme.LIGHT)),390,844,1f,false,access = disabled),
            Frame("large-font",Theme.LIGHT,SettingsUiState(SettingsPhase.Content,Settings(theme = Theme.LIGHT)),320,720,2f,false),
            Frame("landscape",Theme.DARK,SettingsUiState(SettingsPhase.Content,Settings(theme = Theme.DARK)),844,390,1f,true),
            Frame("bottom",Theme.LIGHT,SettingsUiState(SettingsPhase.Content,Settings(theme = Theme.LIGHT)),390,844,1f,false,scrollBottom = true),
        )
        val current = mutableStateOf(frames.first())
        var density = 1f
        compose.setContent {
            val frame = current.value
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val original = LocalDensity.current
                val scaled = min(with(original) { maxWidth.toPx() } / frame.width,with(original) { maxHeight.toPx() } / frame.height)
                val configuration = Configuration(LocalConfiguration.current).apply {
                    fontScale = frame.font
                    screenWidthDp = frame.width; screenHeightDp = frame.height
                    orientation = if(frame.width > frame.height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    frame.night?.let { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(it) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
                }
                SideEffect { density = scaled }
                CompositionLocalProvider(LocalDensity provides Density(scaled,frame.font),LocalConfiguration provides configuration) {
                    Box(Modifier.requiredSize(frame.width.dp,frame.height.dp).testTag("ui06_viewport")) {
                        SettingsDemo(frame.state,frame.theme,notifications = frame.access)
                    }
                }
            }
        }
        val artifacts = ReviewArtifacts("r4b-settings")
        for(frame in frames) {
            compose.runOnIdle { current.value = frame }
            compose.waitForIdle()
            if(frame.openOffsets) compose.onNodeWithTag("settings_payment_offsets").performScrollTo().performClick()
            if(frame.scrollBottom) compose.onNodeWithText("Локальный трекер подписок. Без аккаунта, банковских подключений и синхронизации с сервером.").performScrollTo()
            if(frame.variant == "animations-off") compose.onNodeWithText("Выключены").assertIsDisplayed()
            if(frame.variant == "notifications-disabled") compose.onNodeWithText("Уведомления отключены в системе").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("settings_time_dialog").assertDoesNotExist()
            compose.onAllNodes(hasText("Резерв",substring = true)).assertCountEquals(0)
            artifacts.capture(compose,ReviewScene.Settings,frame.theme.name.lowercase(),frame.font,density,frame.variant)
            if(frame.openOffsets) compose.onNodeWithText("Готово").performClick()
        }
        artifacts.finish()
    }
}
