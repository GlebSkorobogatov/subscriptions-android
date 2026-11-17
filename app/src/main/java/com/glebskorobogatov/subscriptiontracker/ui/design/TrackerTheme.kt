package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme

object TrackerDesign {
    val colors: TrackerColors
        @Composable @ReadOnlyComposable get() = LocalTrackerColors.current
}

internal fun trackerColorScheme(c: TrackerColors, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.primaryButtonBg, onPrimary = c.primaryButtonText,
        primaryContainer = c.surfaceSubtle, onPrimaryContainer = c.textPrimary,
        inversePrimary = c.primaryButtonText,
        secondary = c.textSecondary, onSecondary = c.surface,
        secondaryContainer = c.selectedTabBg, onSecondaryContainer = c.textPrimary,
        tertiary = c.textSecondary, onTertiary = c.surface,
        tertiaryContainer = c.surfaceSubtle, onTertiaryContainer = c.textPrimary,
        background = c.background, onBackground = c.textPrimary,
        surface = c.surface, onSurface = c.textPrimary,
        surfaceVariant = c.surfaceSubtle, onSurfaceVariant = c.textSecondary,
        surfaceTint = Color.Transparent,
        surfaceDim = c.background, surfaceBright = c.surface,
        surfaceContainerLowest = c.background, surfaceContainerLow = c.surfaceSubtle,
        surfaceContainer = c.surfaceSubtle, surfaceContainerHigh = c.surfaceRaised,
        surfaceContainerHighest = c.selectedTabBg,
        inverseSurface = if (dark) LightTrackerColors.surface else DarkTrackerColors.surface,
        inverseOnSurface = if (dark) LightTrackerColors.textPrimary else DarkTrackerColors.textPrimary,
        outline = c.textSecondary, outlineVariant = c.divider, error = c.error,
        onError = c.onError, errorContainer = c.errorContainer, onErrorContainer = c.onErrorContainer,
    )
}
private val Light = trackerColorScheme(LightTrackerColors, false)
private val Dark = trackerColorScheme(DarkTrackerColors, true)

internal fun resolveDarkTheme(theme: Theme, systemDark: Boolean) = when (theme) {
    Theme.SYSTEM -> systemDark
    Theme.LIGHT -> false
    Theme.DARK -> true
}

@Composable
fun TrackerTheme(theme: Theme = Theme.SYSTEM, content: @Composable () -> Unit) {
    val dark = resolveDarkTheme(theme, isSystemInDarkTheme())
    CompositionLocalProvider(LocalTrackerColors provides if (dark) DarkTrackerColors else LightTrackerColors) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = TrackerTypography, shapes = TrackerShapes.material) {
            Surface(Modifier.fillMaxSize().testTag(if (dark) "theme_dark" else "theme_light"),
                color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}
