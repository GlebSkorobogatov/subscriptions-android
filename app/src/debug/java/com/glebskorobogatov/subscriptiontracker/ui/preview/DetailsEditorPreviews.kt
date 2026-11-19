package com.glebskorobogatov.subscriptiontracker.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionDetails
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.design.TrackerTheme
import com.glebskorobogatov.subscriptiontracker.ui.details.*
import com.glebskorobogatov.subscriptiontracker.ui.editor.*
import java.time.*
import java.util.UUID

/** Fictional debug-only data; never inserted into Room or initialized by the launcher. */
internal object DetailsEditorPreviewData {
    val day = LocalDate.of(2027,3,16)
    val id = UUID.fromString("00000000-0000-0000-0000-000000000031")
    val row = SubscriptionEntity(id,"Видеосервис","Семейный",79900,
        FrequencyUnit.MONTH,1,SubscriptionState.ACTIVE,2027,1,31,
        LocalDate.of(2027,3,1),LocalDate.of(2027,3,31),null,null,null,null,
        Instant.parse("2027-01-31T12:00:00Z"),Instant.parse("2027-03-01T12:00:00Z"),null)
    val details = DetailsUiState(day,DetailsPhase.Content,SubscriptionDetails(row,
        SubscriptionPeriod(LocalDate.of(2027,3,31),15,0.5)))
    val editor = EditorState(loading = false,fields = mapOf("name" to row.name,"label" to "Семейный","amount" to "799,00",
        "unit" to "MONTH","count" to "1","date" to "2027-03-31",
        "anchorDay" to "31","start" to "2027-03-01"))
}

@Composable internal fun DetailsDemo(state: DetailsUiState = DetailsEditorPreviewData.details,theme: Theme = Theme.SYSTEM,
    onBack: () -> Unit = {},onEdit: () -> Unit = {},onAction: (DetailsAction) -> Unit = {},onRefresh: () -> Unit = {}) {
    var menu by rememberSaveable { mutableStateOf(false) }
    TrackerTheme(theme) {
        Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            DetailsPane(state, DetailsEditorPreviewData.id, menu, { menu = it }, onBack, onEdit, onBack, onAction, onRefresh)
        } }
    }
}

@Composable internal fun EditorDemo(state: EditorState = DetailsEditorPreviewData.editor, theme: Theme = Theme.SYSTEM,
    editing: Boolean = true,onChange: (String,String) -> Unit = {_,_ ->},onSave: () -> Unit = {},onExit: () -> Unit = {},
    onCatalog: (String) -> Unit = {}, onPickImage: () -> Unit = {}) {
    var catalogOpen by rememberSaveable { mutableStateOf(false) }
    var dateKey by rememberSaveable { mutableStateOf<String?>(null) }
    TrackerTheme(theme) {
        Scaffold(topBar = { EditorHeader(stringResource(if(editing) R.string.editor_edit_title else R.string.editor_title)) {
            when {
                dateKey != null -> dateKey = null
                catalogOpen -> catalogOpen = false
                else -> onExit()
            }
        } }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                EditorForm(state,onChange,onSave,{},{},onExit,{ dateKey = it },
                    onPickCatalog = { catalogOpen = true },
                    onChangeFrequency = { unit, count -> onChange("unit", unit); onChange("count", count) })
            }
        }
        if (catalogOpen) CatalogPickerDialog(state.catalogLogoId, { catalogOpen = false }, { id -> onCatalog(id); catalogOpen = false },
            onPickImage = { catalogOpen = false; onPickImage() })
        dateKey?.let { key ->
            EditorDateSheet(initialDateMillis(state.fields[key]), { dateKey = null }) { millis ->
                onChange(key, epochDayDate(millis).toString())
            }
        }
    }
}

@Preview(name = "Детали • светлая", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Детали • тёмная", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Детали • шрифт ×2", widthDp = 320, heightDp = 720, fontScale = 2f)
@Composable private fun DetailsPreview() = DetailsDemo()

@Preview(name = "Редактор • светлая", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Редактор • тёмная", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Редактор • шрифт ×2", widthDp = 320, heightDp = 720, fontScale = 2f)
@Composable private fun EditorPreview() = EditorDemo()

@Preview(name = "Создание", widthDp = 390, heightDp = 844)
@Composable private fun CreatePreview() = EditorDemo(DetailsEditorPreviewData.editor.copy(fields = DetailsEditorPreviewData.editor.fields + ("name" to "")),editing = false)
