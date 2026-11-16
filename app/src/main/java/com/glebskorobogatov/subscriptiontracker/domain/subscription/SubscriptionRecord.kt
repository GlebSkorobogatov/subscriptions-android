package com.glebskorobogatov.subscriptiontracker.domain.subscription

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.LocalDate
import java.time.YearMonth

/** Positive RUB price. The legacy Money type remains available to pre-R1b consumers. */
data class RubPrice(val amountMinor: Long) {
    init { require(amountMinor > 0) { "Price must be positive" } }
    fun format(): String = Money(amountMinor, RUB).format()
    fun multipliedBy(count: Long): RubPrice = RubPrice(Math.multiplyExact(amountMinor, count))
    operator fun plus(other: RubPrice): RubPrice = RubPrice(Math.addExact(amountMinor, other.amountMinor))
    companion object {
        private val RUB = CurrencyCode.of("RUB")
        fun parse(input: String): RubPrice = fromLegacy(Money.parse(input, RUB))
        /** Reject foreign amounts; never relabel or convert them. */
        fun fromLegacy(money: Money): RubPrice {
            require(money.currency == RUB) { "Only RUB is supported" }
            return RubPrice(money.amountMinor)
        }
    }
}

/** A series without payment confirmation, mutable cycle position or one-off dates. */
data class RecurringSchedule(val anchor: RenewalAnchor, val frequency: Frequency) {
    init { DefaultRenewalCalendar().plannedCycleDate(calendarInput(), 0) }
    // Compatibility boundary to the existing calendar engine, not a second date algorithm.
    internal fun calendarInput(): RenewalSchedule = RenewalSchedule(anchor, frequency)
    companion object {
        fun starting(firstDue: LocalDate, frequency: Frequency, anchorDay: Int? = null): RecurringSchedule {
            val schedule = RecurringSchedule(
                RenewalAnchor(YearMonth.from(firstDue), anchorDay ?: firstDue.dayOfMonth), frequency)
            require(DefaultRenewalCalendar().plannedCycleDate(schedule.calendarInput(), 0) == firstDue) {
                "Anchor must agree with the first expected date"
            }
            return schedule
        }
    }
}

enum class SubscriptionState { ACTIVE, CANCELLED, ARCHIVED }

/** Scope a manual period start to exactly one end boundary. */
data class PeriodStart(val start: LocalDate, val due: LocalDate)

/** Asset references are opaque: storage owns validation and safe cleanup in R1b. */
data class SubscriptionAppearance(val catalogLogoId: String? = null, val logoAssetId: String? = null)

data class SubscriptionRecord(
    val id: SubscriptionId,
    val name: String,
    val price: RubPrice,
    val schedule: RecurringSchedule,
    val accountLabel: String? = null,
    val appearance: SubscriptionAppearance = SubscriptionAppearance(),
    val state: SubscriptionState = SubscriptionState.ACTIVE,
    val periodStart: PeriodStart? = null,
    val cancelledAccessEndExclusive: LocalDate? = null,
    val frozenCancelledPeriodStart: LocalDate? = null,
    val archivedAt: LocalDate? = null,
) {
    init {
        require(name.isNotBlank()) { "Name is required" }
        require(state != SubscriptionState.ACTIVE ||
            (cancelledAccessEndExclusive == null && frozenCancelledPeriodStart == null))
        require(state == SubscriptionState.ARCHIVED || archivedAt == null)
    }
    /** Shared eligibility contract for future statistics and reminder consumers. */
    val participatesInCalculations: Boolean get() = state == SubscriptionState.ACTIVE
}
