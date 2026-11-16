package com.glebskorobogatov.subscriptiontracker.data.settings

import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode
import java.time.LocalTime

enum class Theme { SYSTEM, LIGHT, DARK }
data class Settings(
    val theme: Theme = Theme.SYSTEM,
    val reminderLocalTime: LocalTime = LocalTime.of(9, 0),
    val paymentOffsetsDefault: Set<Int> = setOf(3, 1),
    val cancellationOffsetsDefault: Set<Int> = setOf(7, 3),
    val chartHorizonMonths: Int = 3,
    val selectedChartCurrency: CurrencyCode? = null,
    val version: Int = 1,
) {
    init {
        require(paymentOffsetsDefault.all { it in setOf(7, 3, 1, 0) })
        require(cancellationOffsetsDefault.all { it in setOf(7, 3, 1, 0) })
        require(chartHorizonMonths in setOf(3, 6, 12))
        require(version == 1)
    }
}

/** No writes to Room; absent overrides inherit independently for each event type. */
fun effectiveOffsets(settings: Settings, type: ReminderEventType, override: ReminderOverrideEntity?): Set<Int> {
    require(override == null || override.eventType == type)
    return if (override?.mode == ReminderMode.CUSTOM) override.offsets.toSet()
    else when (type) {
        ReminderEventType.PAYMENT -> settings.paymentOffsetsDefault.toSet()
        ReminderEventType.CANCELLATION -> settings.cancellationOffsetsDefault.toSet()
    }
}
