package com.glebskorobogatov.subscriptiontracker.ui.editor

internal enum class EditorExitResolution {
    /** This exit was already accepted. */
    AlreadyHandled,
    /** A save is in progress. Keep the same request until it finishes. */
    Pending,
    /** The save completed and owns leaving the editor. Do not discard the saved draft. */
    SavedOwnsExit,
    /** Ask the editor to leave, which confirms a dirty draft. */
    Leave,
}

internal fun resolveEditorExit(exitRequest: Int, lastExit: Int, saving: Boolean, done: Boolean): EditorExitResolution = when {
    exitRequest == lastExit -> EditorExitResolution.AlreadyHandled
    saving -> EditorExitResolution.Pending
    done -> EditorExitResolution.SavedOwnsExit
    else -> EditorExitResolution.Leave
}
