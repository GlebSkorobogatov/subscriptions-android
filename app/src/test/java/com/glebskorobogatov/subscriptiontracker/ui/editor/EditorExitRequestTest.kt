package com.glebskorobogatov.subscriptiontracker.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorExitRequestTest {
    @Test fun saveInProgressKeepsTheExitPending() {
        assertEquals(EditorExitResolution.Pending, resolveEditorExit(exitRequest = 1, lastExit = 0, saving = true, done = false))
        assertEquals(EditorExitResolution.Pending, resolveEditorExit(exitRequest = 3, lastExit = 0, saving = true, done = false))
    }

    @Test fun finishedSaveOwnsTheExitWithoutDiscarding() {
        assertEquals(EditorExitResolution.SavedOwnsExit, resolveEditorExit(exitRequest = 1, lastExit = 0, saving = false, done = true))
    }

    @Test fun failedSaveStillAsksBeforeLeavingADirtyDraft() {
        assertEquals(EditorExitResolution.Leave, resolveEditorExit(exitRequest = 1, lastExit = 0, saving = false, done = false))
    }

    @Test fun acceptedExitIsNotAskedAgain() {
        assertEquals(EditorExitResolution.AlreadyHandled, resolveEditorExit(exitRequest = 2, lastExit = 2, saving = false, done = false))
    }
}
