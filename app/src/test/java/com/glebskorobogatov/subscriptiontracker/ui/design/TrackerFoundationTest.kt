package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState
import org.junit.Assert.*
import org.junit.Test

class TrackerFoundationTest {
    private val active = SubscriptionState.ACTIVE
    @Test fun approvedBackgroundsAreExactAndSurfacesAreNeutral() {
        assertEquals(Color.White,LightTrackerColors.background)
        assertEquals(Color.White,LightTrackerColors.surface)
        assertEquals(Color(0xFF212121),DarkTrackerColors.background)
        assertEquals(Color(0xFF212121),DarkTrackerColors.surface)
        assertEquals(Color(0xFFF0F0EE),LightTrackerColors.surfaceSubtle)
        assertEquals(Color(0xFF303030),DarkTrackerColors.surfaceSubtle)
        assertEquals(Color(0xFF303030),DarkTrackerColors.surfaceRaised)
        assertEquals(Color(0xFF505050),DarkTrackerColors.selectedTabBg)
    }
    @Test fun materialThemeUsesCentralRolesForBothPalettes() {
        for((c,dark) in listOf(LightTrackerColors to false,DarkTrackerColors to true)) {
            val scheme = trackerColorScheme(c,dark)
            assertEquals(c.background,scheme.background); assertEquals(c.surface,scheme.surface)
            assertEquals(c.textPrimary,scheme.onSurface); assertEquals(c.textSecondary,scheme.onSurfaceVariant)
            assertEquals(c.surfaceSubtle,scheme.surfaceContainer); assertEquals(c.surfaceRaised,scheme.surfaceContainerHigh)
            assertEquals(c.primaryButtonBg,scheme.primary); assertEquals(c.primaryButtonText,scheme.onPrimary)
            assertEquals(c.selectedTabBg,scheme.secondaryContainer); assertEquals(c.error,scheme.error)
            assertEquals(c.onError,scheme.onError); assertEquals(Color.Transparent,scheme.surfaceTint)
        }
    }
    @Test fun semanticColorsRetainApprovedFunctionalRoles() {
        assertEquals(Color(0xFF54816B),LightTrackerColors.statusCalm)
        assertEquals(Color(0xFFA97732),LightTrackerColors.statusSoon)
        assertEquals(Color(0xFFB42318),LightTrackerColors.error)
        assertEquals(Color(0xFF86B59A),DarkTrackerColors.statusCalm)
        assertEquals(Color(0xFFD7AD6A),DarkTrackerColors.statusSoon)
        assertEquals(Color(0xFFFFB4AB),DarkTrackerColors.error)
        assertEquals(Color(0xFFBFC2C8),DarkTrackerColors.textSecondary)
    }
    @Test fun typographyUsesSystemSansAndApprovedScales() {
        assertEquals(28.sp,TrackerTypography.headlineLarge.fontSize)
        assertEquals(FontWeight.SemiBold,TrackerTypography.headlineLarge.fontWeight)
        assertEquals(20.sp,TrackerTypography.titleLarge.fontSize)
        assertEquals(16.sp,TrackerTypography.titleSmall.fontSize)
        assertEquals(FontWeight.Medium,TrackerTypography.titleSmall.fontWeight)
        assertEquals(13.sp,TrackerTypography.bodySmall.fontSize)
        assertEquals("tnum",TrackerMoneyStyle.fontFeatureSettings)
        assertEquals(androidx.compose.ui.text.font.FontFamily.SansSerif,TrackerMoneyStyle.fontFamily)
    }
    @Test fun dimensionsAreMinimumsNotFixedTextOrRowHeights() {
        assertEquals(54.dp,TrackerDimensions.logoSize); assertEquals(15.dp,TrackerDimensions.logoRadius)
        assertEquals(22.dp,TrackerDimensions.cardRadius); assertEquals(16.dp,TrackerDimensions.fieldRadius)
        assertEquals(52.dp,TrackerDimensions.buttonHeight); assertEquals(48.dp,TrackerDimensions.minimumTouchTarget)
        assertEquals(4.dp,TrackerDimensions.progressHeight)
        assertEquals(16.dp,TrackerDimensions.horizontalPadding(320.dp))
        assertEquals(20.dp,TrackerDimensions.horizontalPadding(390.dp))
    }
    @Test fun twentyPercentAndThreeDaysIsAmber() { assertEquals(PeriodTone.SOON,periodTone(0.2,3,active)) }
    @Test fun moreThanTwentyPercentIsNeutralEvenWithThreeDays() {
        assertEquals(PeriodTone.NEUTRAL,periodTone(Math.nextUp(0.2),3,active))
        assertEquals(PeriodTone.NEUTRAL,periodTone(0.2000000001,3,active))
        // These values collapse to the same Float; the projection must preserve Double.
        assertEquals(0.2f,0.2000000001.toFloat())
    }
    @Test fun moreThanThreeDaysIsNeutralEvenAtTwentyPercent() { assertEquals(PeriodTone.NEUTRAL,periodTone(0.2,4,active)) }
    @Test fun todayIsAmberWhenFractionIsKnown() { assertEquals(PeriodTone.SOON,periodTone(0.0,0,active)) }
    @Test fun unknownOrInvalidPeriodNeverInventsProgress() {
        for(value in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-0.1,1.1))
            assertEquals(PeriodTone.HIDDEN,periodTone(value,2,active))
    }
    @Test fun unknownDaysAndNegativeDaysDoNotInventUrgency() {
        assertEquals(PeriodTone.NEUTRAL,periodTone(0.1,null,active))
        assertEquals(PeriodTone.NEUTRAL,periodTone(0.1,-1,active))
    }
    @Test fun cancelledUsesSecondaryAndArchivedHasNoIndicator() {
        assertEquals(PeriodTone.CANCELLED,periodTone(0.1,1,SubscriptionState.CANCELLED))
        assertEquals(PeriodTone.HIDDEN,periodTone(0.5,10,SubscriptionState.ARCHIVED))
        assertEquals(PeriodTone.HIDDEN,periodTone(null,2,SubscriptionState.CANCELLED))
    }
    @Test fun endpointsAreValidWithoutRedOverdueState() {
        assertEquals(PeriodTone.NEUTRAL,periodTone(1.0,30,active))
        assertEquals(PeriodTone.SOON,periodTone(0.0,0,active))
        assertEquals(listOf(PeriodTone.HIDDEN,PeriodTone.NEUTRAL,PeriodTone.SOON,PeriodTone.CANCELLED),PeriodTone.entries)
    }
    @Test fun moneyUsesExactExistingFormatterAndRussianPresentation() {
        assertEquals("1 990 ₽",formatTrackerRub(199000))
        assertEquals("299,50 ₽",formatTrackerRub(29950))
        assertEquals("0,01 ₽",formatTrackerRub(1)); assertEquals("0,10 ₽",formatTrackerRub(10))
    }
    @Test fun zeroAndLargestAmountDoNotRoundOrOverflow() {
        assertEquals("0 ₽",formatTrackerRub(0))
        assertEquals("92 233 720 368 547 758,07 ₽",formatTrackerRub(Long.MAX_VALUE))
    }
    @Test fun negativeAmountIsRejectedByExistingMoneyContract() { assertThrows(IllegalArgumentException::class.java) { formatTrackerRub(-1) } }
    @Test fun reducedMotionAndFuturePreferenceUseSameNominalDuration() {
        assertEquals(220,TrackerMotion.durationMillis(true,1f))
        assertEquals(0,TrackerMotion.durationMillis(false,1f))
        assertEquals(0,TrackerMotion.durationMillis(true,0f))
        assertEquals(0,TrackerMotion.durationMillis(true,Float.NaN))
    }
}
