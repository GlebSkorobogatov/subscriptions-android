package com.glebskorobogatov.subscriptiontracker.ui.editor

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun openDialogKeepsTheFirstRequestId() {
        val opened = ExitBinding().confirmExit(11L).arm(live = 11L)
        val duringNewer = opened.confirmExit(12L)
        assertEquals(11L, duringNewer.sequence)
        assertEquals(11L, duringNewer.finish().let { opened.sequence })
        assertNull(duringNewer.finish().sequence)
    }

    @Test fun secondRequestBeforeTheDialogComposesDoesNotReplaceTheFirst() {
        val requested = ExitBinding().confirmExit(11L).confirmExit(12L)
        assertEquals(11L, requested.arm(live = 12L).sequence)
    }

    @Test fun dialogOpenedWithoutANotificationDoesNotAdoptALaterRequest() {
        val user = ExitBinding().arm(live = null)
        assertNull(user.confirmExit(4L).sequence)
        assertNull(user.finish().sequence)
    }

    @Test fun restoredDialogKeepsTheFirstIdAndAUserDialogStaysUnbound() {
        val restored = roundTrip(ExitBinding(armed = true, sequence = 11L))
        assertEquals(11L, restored.sequence)
        assertTrue(restored.armed)
        assertEquals(11L, restored.confirmExit(12L).sequence)
        val restoredUser = roundTrip(ExitBinding(armed = true))
        assertNull(restoredUser.sequence)
        assertNull(restoredUser.confirmExit(4L).sequence)
        val cleared = roundTrip(restored.finish())
        assertNull(cleared.sequence)
        assertEquals(false, cleared.armed)
    }

    private fun roundTrip(value: ExitBinding): ExitBinding {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(ExitBindingSaver) { with(scope) { requireNotNull(save(value)) } }
        return requireNotNull(ExitBindingSaver.restore(saved))
    }

    @Test fun savingBackDoesNotCloseTheSheetOrTheEditor() {
        assertEquals(listOf(EditorBackEffect.Ignore), editorBackEffects(sheetOpen = true, external = true, saving = true, dirty = true))
        assertEquals(listOf(EditorBackEffect.Ignore), editorBackEffects(sheetOpen = false, external = false, saving = true, dirty = true))
    }
}
