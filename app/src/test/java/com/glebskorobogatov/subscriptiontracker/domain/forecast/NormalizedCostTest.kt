package com.glebskorobogatov.subscriptiontracker.domain.forecast

import com.glebskorobogatov.subscriptiontracker.domain.model.Frequency
import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit.*
import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import com.glebskorobogatov.subscriptiontracker.domain.model.SubscriptionId
import com.glebskorobogatov.subscriptiontracker.domain.subscription.RecurringSchedule
import com.glebskorobogatov.subscriptiontracker.domain.subscription.RubPrice
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionRecord
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class NormalizedCostTest {
    private val january = LocalDate.of(2027, 1, 1)

    private fun sub(id: Long, price: Long, unit: FrequencyUnit, count: Int = 1, due: LocalDate = january) =
        SubscriptionRecord(SubscriptionId(UUID(0, id)), "Учебный тариф", RubPrice(price),
            RecurringSchedule.starting(due, Frequency(unit, count)))

    private fun assertExact(expected: String, actual: ExactKopecks) =
        assertEquals(expected, "${actual.numerator}/${actual.denominator}")

    @Test fun tStat03SeparatesTariffEstimateFromCalendarCharges() {
        val records = listOf(sub(1, 199_000, MONTH), sub(2, 29_900, MONTH), sub(3, 120_000, YEAR))
        val normalized = NormalizedCostEngine.calculate(records)
        assertEquals(2_866_800L, normalized.total.annualMinor)
        assertEquals(238_900L, normalized.total.monthlyMinor)
        assertExact("2866800/1", normalized.total.exactAnnual)
        assertExact("238900/1", normalized.total.exactMonthly)
        val calendar = ForecastEngine().calculate(records, january)
        assertEquals(348_900L, calendar.months.getValue(YearMonth.of(2027, 1)).totalMinor)
        assertEquals(228_900L, calendar.months.getValue(YearMonth.of(2027, 2)).totalMinor)
    }

    @Test fun tStat04IncludesAllUnitsAndQuarterHalfYearAndLongerTerms() {
        val records = listOf(
            sub(1, 10_000, WEEK), sub(2, 50_000, DAY, 30), sub(3, 50_000, MONTH),
            sub(4, 90_000, MONTH, 3), sub(5, 120_000, YEAR), sub(6, 120_000, YEAR, 2),
            sub(7, 90_000, MONTH, 6), sub(8, 100, DAY),
        )
        val results = NormalizedCostEngine.calculate(records).ranked.associate { it.subscriptionId.value.leastSignificantBits to it.amounts }
        assertEquals(listOf(521_429L, 608_333L, 600_000L, 360_000L, 120_000L, 60_000L, 180_000L, 36_500L),
            (1L..8L).map { results.getValue(it).annualMinor })
        assertEquals(listOf(43_452L, 50_694L, 50_000L, 30_000L, 10_000L, 5_000L, 15_000L, 3_042L),
            (1L..8L).map { results.getValue(it).monthlyMinor })
        assertExact("3650000/7", results.getValue(1).exactAnnual)
        assertExact("912500/21", results.getValue(1).exactMonthly)
    }

    @Test fun halfUpAndMonthlyCalculationUseExactAnnualAmount() {
        val costs = NormalizedCostEngine.calculate(listOf(sub(1, 11, YEAR, 2), sub(2, 6, YEAR))).ranked
        val below = costs.single { it.subscriptionId.value.leastSignificantBits == 1L }.amounts
        assertExact("11/2", below.exactAnnual)
        assertEquals(6L, below.annualMinor)
        assertExact("11/24", below.exactMonthly)
        assertEquals(0L, below.monthlyMinor)
        val half = costs.single { it.subscriptionId.value.leastSignificantBits == 2L }.amounts
        assertExact("1/2", half.exactMonthly)
        assertEquals(1L, half.monthlyMinor)
    }

    @Test fun totalsSumFractionsBeforeRoundingEvenWhenEveryRowRoundsToZero() {
        val annual = NormalizedCostEngine.calculate(listOf(sub(1, 1, YEAR, 3), sub(2, 1, YEAR, 3)))
        assertEquals(listOf(0L, 0L), annual.ranked.map { it.amounts.annualMinor })
        assertExact("2/3", annual.total.exactAnnual)
        assertEquals(1L, annual.total.annualMinor)
        val monthly = NormalizedCostEngine.calculate(listOf(sub(1, 3, YEAR), sub(2, 3, YEAR)))
        assertEquals(listOf(0L, 0L), monthly.ranked.map { it.amounts.monthlyMinor })
        assertExact("1/2", monthly.total.exactMonthly)
        assertEquals(1L, monthly.total.monthlyMinor)
    }

    @Test fun intermediateNumeratorAndWeeklyDenominatorCanExceedPrimitiveMultiplicationRanges() {
        val monthly = NormalizedCostEngine.calculate(listOf(sub(1, Long.MAX_VALUE, MONTH, 12))).total
        assertEquals(Long.MAX_VALUE, monthly.annualMinor)
        val weekly = NormalizedCostEngine.calculate(listOf(sub(1, Long.MAX_VALUE, WEEK, Int.MAX_VALUE))).total
        assertExact("480932970493141881365/2147483647", weekly.exactAnnual)
        assertEquals(223_951_866_253L, weekly.annualMinor)
        assertEquals(18_662_655_521L, weekly.monthlyMinor)
        val tiny = NormalizedCostEngine.calculate(listOf(sub(1, 1, WEEK, Int.MAX_VALUE))).total
        assertExact("365/15032385529", tiny.exactAnnual)
        assertEquals(0L, tiny.annualMinor)
    }

    @Test fun overflowingRowAndOverflowingTotalEachFailExplicitly() {
        assertThrows(ArithmeticException::class.java) {
            NormalizedCostEngine.calculate(listOf(sub(1, Long.MAX_VALUE, MONTH)))
        }
        val large = sub(1, Long.MAX_VALUE, YEAR)
        val small = sub(2, 1, YEAR, 2)
        assertEquals(Long.MAX_VALUE, NormalizedCostEngine.calculate(listOf(large)).total.annualMinor)
        assertEquals(1L, NormalizedCostEngine.calculate(listOf(small)).total.annualMinor)
        assertThrows(ArithmeticException::class.java) { NormalizedCostEngine.calculate(listOf(large, small)) }
    }

    @Test fun ranksByExactAmountThenCanonicalIdEvenWhenRoundedValuesAndNamesCoincide() {
        val lower = sub(1, 1, YEAR, 4)
        val higher = sub(3, 1, YEAR, 3)
        val equal = sub(2, 2, YEAR, 6)
        val result = NormalizedCostEngine.calculate(listOf(higher, lower, equal))
        assertEquals(listOf(equal.id, higher.id, lower.id), result.ranked.map { it.subscriptionId })
        assertEquals(listOf(0L, 0L, 0L), result.ranked.map { it.amounts.annualMinor })
        result.ranked.zip(listOf("1/3", "1/3", "1/4")).forEach { (entry, expected) ->
            assertExact(expected, entry.amounts.exactAnnual)
        }
        assertExact("11/12", result.total.exactAnnual)
    }

    @Test fun emptyAndInactiveSetsReturnExactZerosWithoutOverflow() {
        val inactive = listOf(sub(1, Long.MAX_VALUE, DAY).copy(state = SubscriptionState.CANCELLED),
            sub(2, Long.MAX_VALUE, WEEK).copy(state = SubscriptionState.ARCHIVED))
        for (rows in listOf(emptyList(), inactive)) {
            val result = NormalizedCostEngine.calculate(rows)
            assertTrue(result.ranked.isEmpty())
            assertExact("0/1", result.total.exactAnnual)
            assertExact("0/1", result.total.exactMonthly)
            assertEquals(0L, result.total.annualMinor)
            assertEquals(0L, result.total.monthlyMinor)
        }
    }

    @Test fun renewalAnchorAndLeapYearDoNotChangeEstimates() {
        val leap = sub(1, 120_000, YEAR, due = LocalDate.of(2024, 2, 29))
        val ordinary = leap.copy(schedule = RecurringSchedule.starting(LocalDate.of(2027, 3, 15), leap.schedule.frequency))
        assertEquals(NormalizedCostEngine.calculate(listOf(leap)), NormalizedCostEngine.calculate(listOf(ordinary)))
    }

    @Test fun stressOneHundredActiveSubscriptionsWithMixedPeriods() {
        val rows = (0 until 25).flatMap { group ->
            val id = group * 4L
            val price = 100L + id
            listOf(sub(id, price, DAY, 2), sub(id + 1, price, WEEK, 3),
                sub(id + 2, price, MONTH, 6), sub(id + 3, price, YEAR, 2))
        }
        val start = System.nanoTime()
        val result = NormalizedCostEngine.calculate(rows)
        val elapsedMicros = (System.nanoTime() - start) / 1_000L
        println("R6a stress: 100 active rows, ${elapsedMicros} microseconds")
        assertEquals(100, result.ranked.map { it.subscriptionId }.toSet().size)
        assertEquals(96L, result.ranked.first().subscriptionId.value.leastSignificantBits)
        assertExact("15725000/21", result.total.exactAnnual)
        assertExact("3931250/63", result.total.exactMonthly)
        assertEquals(748_810L, result.total.annualMinor)
        assertEquals(62_401L, result.total.monthlyMinor)
    }
}
