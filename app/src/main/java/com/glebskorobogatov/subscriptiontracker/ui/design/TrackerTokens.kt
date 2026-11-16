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

/** UI_DESIGN_SPEC §§2–4. Semantic roles stay independent of any particular screen. */
@Immutable
data class TrackerColors(
    val background: Color, val surface: Color, val surfaceSubtle: Color, val surfaceRaised: Color,
    val textPrimary: Color, val textSecondary: Color, val divider: Color,
    val primaryButtonBg: Color, val primaryButtonText: Color, val selectedTabBg: Color,
    val statusCalm: Color, val statusSoon: Color, val statusUrgent: Color, val error: Color,
)

internal val LightTrackerColors = TrackerColors(
    Color(0xFFF7F7F5), Color(0xFFFFFFFF), Color(0xFFF0F0EE), Color(0xFFFFFFFF),
    Color(0xFF171717), Color(0xFF62656A), Color(0xFFE5E5E2),
    Color(0xFF262626), Color(0xFFFFFFFF), Color(0xFFE9E9E6),
    Color(0xFF54816B), Color(0xFFA97732), Color(0xFFB85B52), Color(0xFFB42318),
)
internal val DarkTrackerColors = TrackerColors(
    Color(0xFF111214), Color(0xFF202124), Color(0xFF292B2E), Color(0xFF26282B),
    Color(0xFFF5F5F5), Color(0xFFB1B4BA), Color(0xFF393B3F),
    Color(0xFFECECEC), Color(0xFF171717), Color(0xFF3A3D41),
    Color(0xFF86B59A), Color(0xFFD7AD6A), Color(0xFFDE8178), Color(0xFFFFB4AB),
)
internal val LocalTrackerColors = staticCompositionLocalOf { LightTrackerColors }

object TrackerDimensions {
    val screenPadding = 20.dp
    val compactScreenPadding = 16.dp
    val sectionSpacing = 24.dp
    val itemSpacing = 12.dp
    val minimumTouchTarget = 48.dp
    val buttonHeight = 52.dp
    val logoSize = 46.dp
    val progressHeight = 4.dp
    val navigationHeight = 68.dp
    val navigationGap = 12.dp
    val navigationMaxWidth = 600.dp
    const val navigationAnimationMillis = 220
}

object TrackerShapes {
    val card = RoundedCornerShape(22.dp)
    val field = RoundedCornerShape(16.dp)
    val logo = RoundedCornerShape(13.dp)
    val capsule = CircleShape
    val material = Shapes(small = logo, medium = field, large = card, extraLarge = card)
}

private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp,
    lineHeight = line.sp, letterSpacing = 0.sp,
)
internal val TrackerTypography = Typography(
    displayLarge = text(32, 40, FontWeight.SemiBold),
    displayMedium = text(30, 38, FontWeight.SemiBold),
    displaySmall = text(28, 36, FontWeight.SemiBold),
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
