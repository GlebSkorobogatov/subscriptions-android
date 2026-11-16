package com.glebskorobogatov.subscriptiontracker.domain.model

import java.math.BigDecimal

/** Exact nonnegative amount. Zero is valid for totals; user-entered prices must be positive. */
data class Money(val amountMinor: Long, val currency: CurrencyCode) {
    init {
        require(amountMinor >= 0) { "Amount must not be negative" }
    }

    fun requirePositive(): Money {
        require(amountMinor > 0) { "Amount must be positive" }
        return this
    }

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(amountMinor, other.amountMinor), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        require(amountMinor >= other.amountMinor) { "Result must not be negative" }
        return Money(Math.subtractExact(amountMinor, other.amountMinor), currency)
    }

    /** Plain fixed-scale decimal, without grouping; comma is supported for Russian input. */
    fun format(decimalSeparator: Char = ','): String {
        require(decimalSeparator == ',' || decimalSeparator == '.') { "Unsupported separator" }
        return BigDecimal.valueOf(amountMinor, currency.fractionDigits)
            .toPlainString().replace('.', decimalSeparator)
    }

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) { "Cannot combine different currencies" }
    }

    companion object {
        private val DECIMAL = Regex("[0-9]+(?:[.,][0-9]+)?")

        /** No signs, grouping, exponent, whitespace, rounding or currency symbols. */
        fun parse(input: String, currency: CurrencyCode): Money {
            require(input.length in 1..64 && DECIMAL.matches(input)) { "Invalid decimal amount" }
            val separatorIndex = input.indexOfFirst { it == '.' || it == ',' }
            val fractionLength = if (separatorIndex < 0) 0 else input.length - separatorIndex - 1
            require(fractionLength <= currency.fractionDigits) { "Too many fractional digits" }
            val minor = try {
                BigDecimal(input.replace(',', '.')).movePointRight(currency.fractionDigits).longValueExact()
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("Amount exceeds the supported range", error)
            }
            return Money(minor, currency).requirePositive()
        }
    }
}
