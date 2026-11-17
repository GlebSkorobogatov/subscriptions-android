package com.glebskorobogatov.subscriptiontracker.data.settings

import java.time.LocalTime

enum class Theme { SYSTEM, LIGHT, DARK }
data class Settings(
    val theme: Theme = Theme.SYSTEM,
    // Keep the persisted v1 key; these are renewal offsets, not actual payment facts.
    val renewalOffsets: Set<Int> = setOf(3, 1),
    /** Missing preference reads as enabled. The settings version stays 1. */
    val animationsEnabled: Boolean = true,
    val version: Int = 1,
) {
    val reminderLocalTime: LocalTime get() = LocalTime.of(9, 0)
    init {
        require(renewalOffsets.all { it in setOf(7, 3, 1, 0) })
        require(version == 1)
    }
}
