package com.glebskorobogatov.subscriptiontracker.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performScrollTo

/** Pinned actions have no scroll parent. Scrolling fields keep the existing action. */
fun SemanticsNodeInteraction.performScrollToIfScrollable(): SemanticsNodeInteraction = try {
    performScrollTo()
} catch (error: AssertionError) {
    if (error.message?.contains("Scroll SemanticsAction") == true) this else throw error
}
