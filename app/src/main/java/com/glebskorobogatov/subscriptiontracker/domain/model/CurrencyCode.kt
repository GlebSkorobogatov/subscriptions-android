package com.glebskorobogatov.subscriptiontracker.domain.model

import java.util.Currency

/** ISO 4217 currency with a defined minor unit in the platform currency registry. */
class CurrencyCode private constructor(val value: String, val fractionDigits: Int) {
    override fun equals(other: Any?): Boolean = other is CurrencyCode && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        fun of(code: String): CurrencyCode {
            require(code.matches(Regex("[A-Z]{3}"))) { "Expected an uppercase ISO 4217 code" }
            val currency = Currency.getInstance(code)
            val digits = currency.defaultFractionDigits
            require(digits >= 0) { "Currency has no defined minor unit: $code" }
            return CurrencyCode(currency.currencyCode, digits)
        }
    }
}
