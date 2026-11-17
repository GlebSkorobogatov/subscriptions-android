package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Same public UI route a user takes; no direct NavController or database mutation. */
internal fun ComposeContentTestRule.openArchive() {
    onNodeWithTag("tab_settings").performClick()
    onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_archive"))
    onNodeWithTag("settings_archive").performClick()
    onNodeWithTag("bottom_navigation").assertDoesNotExist()
}
