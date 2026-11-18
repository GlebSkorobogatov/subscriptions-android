package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Open the details overflow menu. A second call leaves an already open menu open. */
internal fun ComposeTestRule.openDetailsManagement() {
    waitUntil(5000) { onAllNodesWithTag("details_menu").fetchSemanticsNodes().isNotEmpty() }
    if (onAllNodesWithTag("details_action_delete").fetchSemanticsNodes().isEmpty())
        onNodeWithTag("details_menu").performClick()
    waitUntil(5000) { onAllNodesWithTag("details_action_delete").fetchSemanticsNodes().isNotEmpty() }
}
