package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Follow the real UI-05 entry point before retaining the existing checkbox assertions. */
internal fun ComposeContentTestRule.openSettingsOffsets() {
    val checkbox = "settings_payment_0"
    if(onAllNodesWithTag(checkbox).fetchSemanticsNodes().isNotEmpty()) return
    if(onAllNodesWithTag("settings_offsets_dialog").fetchSemanticsNodes().isNotEmpty()) onNodeWithText("Готово").performClick()
    onNodeWithTag("settings_payment_offsets").performScrollTo().performClick()
}
