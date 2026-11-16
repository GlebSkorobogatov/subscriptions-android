package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Open the real disclosure before testing the original lifecycle operations; keep existing deadlines. */
internal fun ComposeTestRule.openDetailsManagement() {
    waitUntil(5000) { onAllNodesWithTag("details_management").fetchSemanticsNodes().isNotEmpty() }
    val node = onNodeWithTag("details_management")
    if (node.fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "" } != "Развёрнуто")
        node.performScrollTo().performClick()
}
