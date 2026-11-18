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

internal enum class EditorBackEffect {
    /** Close the calendar or catalog and do not leave the editor. */
    CloseSheet,
    /** The draft is dirty. Ask before discarding it. */
    ConfirmDirty,
    /** Nothing to keep. Leave the editor. */
    Leave,
    /** A save is in progress. Do not close the editor or the sheet. */
    Ignore,
}

/**
 * Ordinary Back stops after closing a sheet.
 * An external exit, such as a notification, closes that sheet and then continues.
 */
/**
 * The exit dialog keeps the first sequence that armed it.
 * A newer notification must not replace that id, and a dialog opened with no notification
 * must not adopt one that arrives while it is showing.
 */
internal data class ExitBinding(val armed: Boolean = false, val requested: Long? = null, val sequence: Long? = null) {
    fun confirmExit(incoming: Long): ExitBinding = if (armed || requested != null) this else copy(requested = incoming)
    fun arm(live: Long?): ExitBinding = if (armed) this else copy(armed = true, sequence = requested ?: live, requested = null)
    fun finish(): ExitBinding = ExitBinding()
}

internal fun editorBackEffects(sheetOpen: Boolean, external: Boolean, saving: Boolean, dirty: Boolean): List<EditorBackEffect> {
    if (saving) return listOf(EditorBackEffect.Ignore)
    if (!external && sheetOpen) return listOf(EditorBackEffect.CloseSheet)
    return buildList {
        if (sheetOpen) add(EditorBackEffect.CloseSheet)
        add(if (dirty) EditorBackEffect.ConfirmDirty else EditorBackEffect.Leave)
    }
}
