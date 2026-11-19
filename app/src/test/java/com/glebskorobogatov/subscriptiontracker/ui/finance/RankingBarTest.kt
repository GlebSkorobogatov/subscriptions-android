package com.glebskorobogatov.subscriptiontracker.ui.finance

import com.glebskorobogatov.subscriptiontracker.domain.forecast.ExactKopecks
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class RankingBarTest {
    @Test fun roundedZerosStillHaveDistinctExactRelativeWidths() {
        val largest = ExactKopecks.of(BigInteger.ONE, BigInteger.valueOf(3))
        val smaller = ExactKopecks.of(BigInteger.ONE, BigInteger.valueOf(4))
        assertEquals(0L, largest.roundedMinor())
        assertEquals(0L, smaller.roundedMinor())
        assertEquals(1f, rankingBarFraction(largest, largest), 0f)
        assertEquals(0.75f, rankingBarFraction(smaller, largest), 0f)
        assertEquals(0.75f, rankingBarFraction(smaller.monthly(), largest.monthly()), 0f)
    }

    @Test fun ratioRemainsFiniteWhenIndividualRationalTermsWouldOverflowFloatingPoint() {
        val huge = BigInteger.TEN.pow(400)
        val largest = ExactKopecks.of(huge + BigInteger.ONE, huge)
        val smaller = ExactKopecks.of(huge + BigInteger.ONE, huge * BigInteger.TWO)
        assertEquals(0.5f, rankingBarFraction(smaller, largest), 0f)
        assertEquals(0f, rankingBarFraction(ExactKopecks.ZERO, largest), 0f)
        assertEquals(0f, rankingBarFraction(ExactKopecks.ZERO, ExactKopecks.ZERO), 0f)
    }
}
