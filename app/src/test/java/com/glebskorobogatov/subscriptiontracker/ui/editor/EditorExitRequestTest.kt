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

    @Test fun userBackClosesOnlyAnOpenSheet() {
        assertEquals(listOf(EditorBackEffect.CloseSheet), editorBackEffects(sheetOpen = true, external = false, saving = false, dirty = true))
        assertEquals(listOf(EditorBackEffect.CloseSheet), editorBackEffects(sheetOpen = true, external = false, saving = false, dirty = false))
    }

    @Test fun externalExitClosesTheSheetAndContinues() {
        assertEquals(listOf(EditorBackEffect.CloseSheet, EditorBackEffect.ConfirmDirty),
            editorBackEffects(sheetOpen = true, external = true, saving = false, dirty = true))
        assertEquals(listOf(EditorBackEffect.CloseSheet, EditorBackEffect.Leave),
            editorBackEffects(sheetOpen = true, external = true, saving = false, dirty = false))
        assertEquals(listOf(EditorBackEffect.ConfirmDirty), editorBackEffects(sheetOpen = false, external = true, saving = false, dirty = true))
        assertEquals(listOf(EditorBackEffect.Leave), editorBackEffects(sheetOpen = false, external = true, saving = false, dirty = false))
    }

    @Test fun savingBackDoesNotCloseTheSheetOrTheEditor() {
        assertEquals(listOf(EditorBackEffect.Ignore), editorBackEffects(sheetOpen = true, external = true, saving = true, dirty = true))
        assertEquals(listOf(EditorBackEffect.Ignore), editorBackEffects(sheetOpen = false, external = false, saving = true, dirty = true))
    }
}
