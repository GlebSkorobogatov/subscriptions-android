package com.glebskorobogatov.subscriptiontracker.domain.model


/** RUB-only technical formatting/forecast key; subscriptions store RubPrice. */
class CurrencyCode private constructor(val value: String, val fractionDigits: Int) {
    override fun equals(other: Any?): Boolean = other is CurrencyCode && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        fun of(code: String): CurrencyCode {
            require(code == "RUB") { "Only RUB is supported" }
            return CurrencyCode("RUB", 2)
        }
    }
}
