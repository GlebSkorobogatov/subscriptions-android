package com.glebskorobogatov.subscriptiontracker.ui.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs a notification navigation outside the LaunchedEffect that observes it.
 * Clearing the request after a decision must not cancel that same decision.
 * A different sequence or editor mode cancels the previous read before it can navigate.
 */
internal class NotificationNavigationSession {
    private var job: Job? = null
    private var runningSequence: Long? = null
    private var runningEditor: Boolean? = null

    fun dispatch(
        scope: CoroutineScope,
        sequence: Long?,
        editor: Boolean,
        block: suspend () -> Unit,
    ) {
        if (sequence == null) return
        if (runningSequence == sequence && runningEditor == editor && job?.isActive == true) return
        job?.cancel()
        runningSequence = sequence
        runningEditor = editor
        job = scope.launch { block() }
    }
}
