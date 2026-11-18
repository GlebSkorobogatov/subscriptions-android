package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** UI_DESIGN_SPEC 2.1 §1. Semantic roles stay independent of any particular screen. */
@Immutable
data class TrackerColors(
    val background: Color, val surface: Color, val surfaceSubtle: Color, val surfaceRaised: Color,
    val textPrimary: Color, val textSecondary: Color, val divider: Color,
    val primaryButtonBg: Color, val primaryButtonText: Color, val selectedTabBg: Color,
    val statusCalm: Color, val statusSoon: Color, val error: Color,
    val onError: Color, val errorContainer: Color, val onErrorContainer: Color,
)

internal val LightTrackerColors = TrackerColors(
    Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFF0F0EE), Color(0xFFFFFFFF),
    Color(0xFF171717), Color(0xFF62656A), Color(0xFFE5E5E2),
    Color(0xFF262626), Color(0xFFFFFFFF), Color(0xFFE9E9E6),
    Color(0xFF54816B), Color(0xFFA97732), Color(0xFFB42318),
    Color.White, Color(0xFFFFDAD6), Color(0xFF410002),
)
internal val DarkTrackerColors = TrackerColors(
    Color(0xFF212121), Color(0xFF212121), Color(0xFF303030), Color(0xFF303030),
    Color(0xFFF5F5F5), Color(0xFFBFC2C8), Color(0xFF393B3F),
    Color(0xFFECECEC), Color(0xFF171717), Color(0xFF505050),
    Color(0xFF86B59A), Color(0xFFD7AD6A), Color(0xFFFFB4AB),
    Color(0xFF171717), Color(0xFF93000A), Color(0xFFFFDAD6),
)
internal val LocalTrackerColors = staticCompositionLocalOf { LightTrackerColors }

object TrackerDimensions {
    val screenPadding = 20.dp
    val compactScreenPadding = 16.dp
    val sectionSpacing = 24.dp
    val itemSpacing = 12.dp
    val contentSpacing = 16.dp
    val modalPadding = 24.dp
    val minimumTouchTarget = 48.dp
    val buttonHeight = 52.dp
    val logoSize = 54.dp
    // Details hero only. List rows and the editor keep logoSize at 54 dp.
    val detailsLogoSize = 72.dp
    val progressHeight = 4.dp
    val cardRadius = 22.dp
    val fieldRadius = 16.dp
    val logoRadius = 15.dp
    // Reference, never a fixed row height. Rows grow with text and accessibility settings.
    val rowStepReference = 98.dp
    fun horizontalPadding(width: androidx.compose.ui.unit.Dp) = if(width < 360.dp) compactScreenPadding else screenPadding
    val navigationHeight = 60.dp
    val navigationGap = 12.dp
    val navigationMaxWidth = 204.dp
    const val navigationAnimationMillis = TrackerMotion.transitionMillis
}

object TrackerShapes {
    val card = RoundedCornerShape(TrackerDimensions.cardRadius)
    val field = RoundedCornerShape(TrackerDimensions.fieldRadius)
    val logo = RoundedCornerShape(TrackerDimensions.logoRadius)
    val capsule = CircleShape
    val material = Shapes(small = logo, medium = field, large = card, extraLarge = card)
}

private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp,
    lineHeight = line.sp, letterSpacing = 0.sp,
)
internal val TrackerTypography = Typography(
    displayLarge = text(32, 40, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
    displayMedium = text(30, 38, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
    displaySmall = text(28, 36, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
    headlineLarge = text(28, 36, FontWeight.SemiBold),
    headlineMedium = text(28, 36, FontWeight.SemiBold),
    headlineSmall = text(28, 36, FontWeight.SemiBold),
    titleLarge = text(20, 28, FontWeight.SemiBold),
    titleMedium = text(18, 26, FontWeight.SemiBold),
    titleSmall = text(16, 24, FontWeight.Medium),
    bodyLarge = text(16, 24), bodyMedium = text(14, 20), bodySmall = text(13, 20),
    labelLarge = text(14, 20, FontWeight.Medium),
    labelMedium = text(13, 18, FontWeight.Medium), labelSmall = text(12, 18, FontWeight.Medium),
)

/** Exact values formatted elsewhere; this affects glyph geometry only. */
val TrackerMoneyStyle = TrackerTypography.displaySmall.copy(fontFeatureSettings = "tnum")

/** Reusable durations; callers also respect the system MotionDurationScale. No preference storage. */
object TrackerMotion {
    const val transitionMillis = 220
    fun durationMillis(animationsEnabled: Boolean, systemScale: Float): Int =
        if (!animationsEnabled || !systemScale.isFinite() || systemScale <= 0f) 0 else transitionMillis
}
