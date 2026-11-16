package com.glebskorobogatov.subscriptiontracker.data.room

import androidx.room.TypeConverter
import com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Dates are epoch days; timestamps are ISO text retaining nanoseconds. Enums use Room's name converter. */
class StorageConverters {
    @TypeConverter fun uuidToText(value: UUID): String = value.toString()
    @TypeConverter fun textToUuid(value: String): UUID = UUID.fromString(value).also {
        require(it.toString() == value) { "Non-canonical UUID" }
    }
    @TypeConverter fun dateToDay(value: LocalDate): Long = value.toEpochDay()
    @TypeConverter fun dayToDate(value: Long): LocalDate = LocalDate.ofEpochDay(value)
    @TypeConverter fun instantToText(value: Instant): String = value.toString()
    @TypeConverter fun textToInstant(value: String): Instant = Instant.parse(value)
    @TypeConverter fun currencyToText(value: CurrencyCode): String = value.value
    @TypeConverter fun textToCurrency(value: String): CurrencyCode = CurrencyCode.of(value)
    @TypeConverter fun offsetsToText(value: Set<Int>): String {
        require(value.all { it in setOf(0, 1, 3, 7) })
        return value.sorted().joinToString(",")
    }
    @TypeConverter fun textToOffsets(value: String): Set<Int> =
        (if (value.isEmpty()) emptySet() else value.split(',').map(String::toInt).toSet()).also {
            require(offsetsToText(it) == value)
        }
}
