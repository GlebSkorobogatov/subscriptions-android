package com.glebskorobogatov.subscriptiontracker.data.room

import com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class StorageConvertersTest {
    private val converters = StorageConverters()
    @Test fun datesAndNanosecondTimestampsRoundTripExactly() {
        for (date in listOf(LocalDate.MIN, LocalDate.of(2028, 2, 29), LocalDate.MAX)) {
            assertEquals(date, converters.dayToDate(converters.dateToDay(date)))
        }
        val instant = Instant.parse("2026-10-30T12:34:56.123456789Z")
        assertEquals(instant, converters.textToInstant(converters.instantToText(instant)))
    }
    @Test fun uuidAndCurrenciesRoundTrip() {
        val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
        assertEquals(id, converters.textToUuid(converters.uuidToText(id)))

    }

    @Test fun invalidStoredValuesAreNotSilentlyNormalized() {
        assertThrows(IllegalArgumentException::class.java) { converters.textToUuid("1-1-1-1-1") }
    }
}
