package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Follow the real UI-05 entry point before retaining the existing checkbox assertions. */
internal fun ComposeContentTestRule.openSettingsOffsets(payment: Boolean) {
    val checkbox = "settings_${if(payment) "payment" else "cancellation"}_0"
    if(onAllNodesWithTag(checkbox).fetchSemanticsNodes().isNotEmpty()) return
    if(onAllNodesWithTag("settings_offsets_dialog").fetchSemanticsNodes().isNotEmpty()) onNodeWithText("Готово").performClick()
    onNodeWithTag(if(payment) "settings_payment_offsets" else "settings_cancellation_offsets").performScrollTo().performClick()
}
