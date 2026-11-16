package com.glebskorobogatov.subscriptiontracker.ui.finance

import org.junit.Test
import org.junit.Assert.*

class ChartHeightTest {
    @Test fun zerosHaveNoBarEvenWhenEveryMonthIsZero() {
        assertEquals(0,chartHeightPx(0,0,384)); assertEquals(0,chartHeightPx(0,Long.MAX_VALUE,384))
        assertEquals(0,chartHeightPx(Long.MAX_VALUE,Long.MAX_VALUE,0))
    }
    @Test fun heightsAreProportionalAndMaximumReachesTop() {
        assertEquals(384,chartHeightPx(100,100,384)); assertEquals(192,chartHeightPx(50,100,384))
        assertEquals(96,chartHeightPx(25,100,384)); assertEquals(115,chartHeightPx(30,100,384))
    }
    @Test fun longMaximumProductNeverOverflowsAndTinyPositiveValuesRemainVisible() {
        assertEquals(Int.MAX_VALUE,chartHeightPx(Long.MAX_VALUE,Long.MAX_VALUE,Int.MAX_VALUE))
        assertEquals(1,chartHeightPx(1,Long.MAX_VALUE,384))
        assertEquals(383,chartHeightPx(Long.MAX_VALUE-1,Long.MAX_VALUE,384))
    }
    @Test fun outputIsMonotoneBoundedForLargeAndSmallValues() {
        var previous = 0
        for(value in listOf(0L,1L,100L,Long.MAX_VALUE/2,Long.MAX_VALUE-1,Long.MAX_VALUE)) {
            val height = chartHeightPx(value,Long.MAX_VALUE,384)
            assertTrue(height in 0..384); assertTrue(height >= previous); previous = height
        }
    }
    @Test fun invalidGeometryIsRejectedInsteadOfDrawingNegativeOrMisleadingHeights() {
        for(args in listOf(Triple(-1L,1L,100),Triple(1L,0L,100),Triple(1L,1L,-1))) {
            assertThrows(IllegalArgumentException::class.java) { chartHeightPx(args.first,args.second,args.third) }
        }
    }
}
