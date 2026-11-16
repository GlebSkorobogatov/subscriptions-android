package com.glebskorobogatov.subscriptiontracker.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class SettingsDialogStateTest {
    @Test fun oldNullableBooleanDialogStateIsDiscardedAndCurrentGlobalDialogRoundTrips() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saver = renewalOffsetsDialogSaver
        // Previous rememberSaveable stored a nullable Boolean wrapped in MutableState.
        for (old in listOf(mutableStateOf<Boolean?>(null),mutableStateOf<Boolean?>(false),mutableStateOf<Boolean?>(true),false,true,"old")) {
            assertFalse(requireNotNull(saver.restore(old)).value)
        }
        for (open in listOf(false,true)) {
            val encoded = with(saver) { scope.save(mutableStateOf(open)) }
            assertEquals(open,requireNotNull(saver.restore(requireNotNull(encoded))).value)
        }
    }
}
