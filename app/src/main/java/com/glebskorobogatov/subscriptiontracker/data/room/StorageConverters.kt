package com.glebskorobogatov.subscriptiontracker.data.room

import androidx.room.TypeConverter
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
}
