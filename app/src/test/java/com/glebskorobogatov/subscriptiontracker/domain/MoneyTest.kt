package com.glebskorobogatov.subscriptiontracker.domain

import com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode
import com.glebskorobogatov.subscriptiontracker.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {
    private val rub = CurrencyCode.of("RUB")

    @Test fun moneyT01ParsesCommaAndFormatsWithoutLoss() {
        assertEquals(49900L, Money.parse("499,00", rub).amountMinor)
        assertEquals("499,00", Money(49900, rub).format())
    }


    @Test fun moneyT03RejectsMalformedAndNonpositiveInput() {
        for (text in listOf("", "0", "0,00", "-1", "+1", "1e5", "NaN", "Infinity",
            " 1", "1 ", "1 000", "1\u00a0000", "1_000", "1,000.00", "1.2.3", ".1", "1,", "₽1", "١")) {
            val error = assertThrows("Input: $text", IllegalArgumentException::class.java) {
                Money.parse(text, rub)
            }
            assertTrue(!error.message.isNullOrBlank())
        }
    }

    @Test fun moneyT03RejectsExcessPrecisionEvenWhenExtraDigitsAreZero() {
        for (text in listOf("1.001", "1.000", "0.001")) {
            assertThrows(IllegalArgumentException::class.java) { Money.parse(text, rub) }
        }
        assertThrows(IllegalArgumentException::class.java) { Money.parse("1.2345", rub) }
        assertThrows(IllegalArgumentException::class.java) { Money.parse("1".repeat(65), rub) }
    }


    @Test fun currencyCodesAreValidatedAndHaveValueEquality() {
        assertEquals(rub, CurrencyCode.of("RUB"))
        assertEquals(rub.hashCode(), CurrencyCode.of("RUB").hashCode())
        for (code in listOf("rub", "Rub", " RUB", "USD", "JPY", "KWD", "USD ", "$", "ZZZ", "XXX", "XTS", "", "US")) {
            assertThrows("Code: $code", IllegalArgumentException::class.java) { CurrencyCode.of(code) }
        }
    }

    @Test fun largestLongAmountIsAcceptedExactly() {
        assertEquals(Long.MAX_VALUE, Money.parse("92233720368547758,07", rub).amountMinor)
        assertEquals("92233720368547758,07", Money(Long.MAX_VALUE, rub).format())
    }

    @Test fun overflowInputIsRejectedInsteadOfRoundedOrWrapped() {
        for (text in listOf("92233720368547758,08", "9223372036854775807", "999999999999999999999999")) {
            assertThrows(IllegalArgumentException::class.java) { Money.parse(text, rub) }
        }
    }

    @Test fun additionChecksOverflowAtBoundary() {
        assertEquals(Long.MAX_VALUE, (Money(Long.MAX_VALUE - 1, rub) + Money(1, rub)).amountMinor)
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE, rub) + Money(1, rub) }
    }

    @Test fun zeroTotalsAndPositivePricesHaveDistinctContracts() {
        assertEquals("0,00", Money(0, rub).format())
        assertThrows(IllegalArgumentException::class.java) { Money(0, rub).requirePositive() }
        assertThrows(IllegalArgumentException::class.java) { Money(-1, rub) }
        assertThrows(IllegalArgumentException::class.java) { Money(Long.MIN_VALUE, rub) }
        assertThrows(IllegalArgumentException::class.java) { Money(1, rub).copy(amountMinor = -1) }
        assertEquals(Money(1, rub), Money(1, rub).requirePositive())
    }

    @Test fun subtractionNeverProducesNegativeAmount() {
        assertEquals(Money(0, rub), Money(999, rub) - Money(999, rub))
        assertEquals(Money(Long.MAX_VALUE - 1, rub), Money(Long.MAX_VALUE, rub) - Money(1, rub))
        assertThrows(IllegalArgumentException::class.java) { Money(0, rub) - Money(1, rub) }
    }

    @Test fun parseFormatRoundTripsMinorUnitsAcrossScales() {
        for (currency in listOf(rub)) {
            for (minor in listOf(1L, 99L, 100L, 10001L, Long.MAX_VALUE)) {
                val money = Money(minor, currency)
                for (separator in listOf(',', '.')) assertEquals(money, Money.parse(money.format(separator), currency))
            }
        }
        assertEquals(Money(100, rub), Money.parse("0001", rub))
        assertThrows(IllegalArgumentException::class.java) { Money(1, rub).format(' ') }
    }
}
