package com.glebskorobogatov.subscriptiontracker.ui.editor

import com.glebskorobogatov.subscriptiontracker.R

/** Five period choices from the product requirements. Six months is custom, not a sixth capsule. */
internal enum class EditorPeriodPreset(val unit: String, val count: String, val label: Int, val tag: String) {
    WEEK("WEEK", "1", R.string.editor_week, "frequency_week"),
    MONTH("MONTH", "1", R.string.editor_month, "frequency_month"),
    QUARTER("MONTH", "3", R.string.editor_quarter, "frequency_quarter"),
    YEAR("YEAR", "1", R.string.editor_unit_year, "frequency_year"),
    CUSTOM("DAY", "1", R.string.editor_custom, "frequency_custom"),
}

internal fun editorPeriodPreset(unit: String?, count: String?): EditorPeriodPreset = when {
    unit == "WEEK" && count == "1" -> EditorPeriodPreset.WEEK
    unit == "MONTH" && count == "1" -> EditorPeriodPreset.MONTH
    unit == "MONTH" && count == "3" -> EditorPeriodPreset.QUARTER
    unit == "YEAR" && count == "1" -> EditorPeriodPreset.YEAR
    else -> EditorPeriodPreset.CUSTOM
}

/**
 * Custom editing is a presentation mode, not a schedule type.
 * While it is on, MONTH/1 and the other presets stay inside «Другая» until that capsule is tapped.
 * A null mode derives the capsule from unit/count, so an existing six-month series still opens as custom.
 */
internal fun editorFrequencySelection(unit: String?, count: String?, customMode: Boolean?): EditorPeriodPreset =
    if (customMode == true) EditorPeriodPreset.CUSTOM else editorPeriodPreset(unit, count)

/** A hidden anchor or period-start error stays reachable without an extra tap. */
internal fun editorExtraVisible(expanded: Boolean, errors: Map<String, *>): Boolean =
    expanded || "anchorDay" in errors || "start" in errors
