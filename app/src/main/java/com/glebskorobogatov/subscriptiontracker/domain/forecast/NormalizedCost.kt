package com.glebskorobogatov.subscriptiontracker.domain.forecast

import com.glebskorobogatov.subscriptiontracker.domain.model.FrequencyUnit
import com.glebskorobogatov.subscriptiontracker.domain.model.SubscriptionId
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionRecord
import java.math.BigInteger

/** Nonnegative rational amount in kopecks, always reduced with a positive denominator. */
@ConsistentCopyVisibility
data class ExactKopecks private constructor(val numerator: BigInteger, val denominator: BigInteger) :
    Comparable<ExactKopecks> {
    override fun compareTo(other: ExactKopecks): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    internal operator fun plus(other: ExactKopecks): ExactKopecks {
        val common = denominator.gcd(other.denominator)
        val left = denominator / common
        val right = other.denominator / common
        return of(numerator * right + other.numerator * left, denominator * right)
    }

    internal fun monthly(): ExactKopecks = of(numerator, denominator * BigInteger.valueOf(12))

    /** HALF_UP for nonnegative amounts. The checked conversion can fail only at this boundary. */
    internal fun roundedMinor(): Long {
        val (whole, remainder) = numerator.divideAndRemainder(denominator)
        val rounded = if (remainder.shiftLeft(1) >= denominator) whole + BigInteger.ONE else whole
        return rounded.longValueExact()
    }

    companion object {
        internal val ZERO = ExactKopecks(BigInteger.ZERO, BigInteger.ONE)

        internal fun of(numerator: BigInteger, denominator: BigInteger): ExactKopecks {
            require(numerator.signum() >= 0 && denominator.signum() > 0)
            val divisor = numerator.gcd(denominator)
            return ExactKopecks(numerator / divisor, denominator / divisor)
        }
    }
}

data class NormalizedAmounts internal constructor(
    val exactAnnual: ExactKopecks,
    val exactMonthly: ExactKopecks,
    val annualMinor: Long,
    val monthlyMinor: Long,
)

data class NormalizedSubscriptionCost(val subscriptionId: SubscriptionId, val amounts: NormalizedAmounts)
data class NormalizedCostResult(val total: NormalizedAmounts, val ranked: List<NormalizedSubscriptionCost>)

/** Tariff estimates for a standard 365-day year. Schedule dates and the selected month are irrelevant. */
object NormalizedCostEngine {
    /** Throws ArithmeticException if any rounded row or total cannot be represented in Long kopecks. */
    fun calculate(subscriptions: List<SubscriptionRecord>): NormalizedCostResult {
        var total = ExactKopecks.ZERO
        val ranked = subscriptions.asSequence()
            .filter { it.participatesInCalculations }
            .map { subscription ->
                val annual = annualCost(subscription)
                total += annual
                NormalizedSubscriptionCost(subscription.id, amounts(annual))
            }
            .sortedWith(compareByDescending<NormalizedSubscriptionCost> { it.amounts.exactAnnual }
                .thenBy { it.subscriptionId.toString() })
            .toList()
        return NormalizedCostResult(amounts(total), ranked)
    }

    private fun annualCost(subscription: SubscriptionRecord): ExactKopecks {
        val price = BigInteger.valueOf(subscription.price.amountMinor)
        val frequency = subscription.schedule.frequency
        val count = BigInteger.valueOf(frequency.count.toLong())
        return when (frequency.unit) {
            FrequencyUnit.DAY -> ExactKopecks.of(price * BigInteger.valueOf(365), count)
            FrequencyUnit.WEEK -> ExactKopecks.of(price * BigInteger.valueOf(365), count * BigInteger.valueOf(7))
            FrequencyUnit.MONTH -> ExactKopecks.of(price * BigInteger.valueOf(12), count)
            FrequencyUnit.YEAR -> ExactKopecks.of(price, count)
        }
    }

    private fun amounts(annual: ExactKopecks): NormalizedAmounts {
        val monthly = annual.monthly()
        return NormalizedAmounts(annual, monthly, annual.roundedMinor(), monthly.roundedMinor())
    }
}
