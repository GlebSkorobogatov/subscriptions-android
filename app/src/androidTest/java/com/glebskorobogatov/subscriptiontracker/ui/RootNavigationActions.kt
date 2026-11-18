package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Same public UI route a user takes; no direct NavController or database mutation.
 * A restored scroll can leave the row's center on the middle tab. Scroll it clear first:
 * the list is allowed to draw behind the island, and the row stays reachable beside or above it. */
internal fun ComposeContentTestRule.openArchive() {
    onNodeWithTag("tab_settings").performClick()
    val settings = onNodeWithTag("settings_screen")
    settings.performScrollToNode(hasTestTag("settings_archive"))
    val island = onAllNodesWithTag("bottom_navigation").fetchSemanticsNodes().firstOrNull()?.boundsInRoot
    val row = onNodeWithTag("settings_archive").fetchSemanticsNode().boundsInRoot
    if(island != null && row.bottom > island.top) {
        settings.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, row.bottom - island.top + 8f) }
        waitForIdle()
    }
    onNodeWithTag("settings_archive").performClick()
    onNodeWithTag("archive_screen").assertExists()
    onNodeWithTag("bottom_navigation").assertDoesNotExist()
}
