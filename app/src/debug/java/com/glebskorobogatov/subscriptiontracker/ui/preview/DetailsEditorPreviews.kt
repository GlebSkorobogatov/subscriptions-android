package com.glebskorobogatov.subscriptiontracker.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionDetails
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
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
    val row = SubscriptionEntity(id,"Видеосервис","Семейный",79900,CurrencyCode.of("RUB"),
        FrequencyUnit.MONTH,1,RenewalMode.AUTO,LifecycleState.ACTIVE,2027,1,31,2,null,null,
        LocalDate.of(2027,3,1),null,3,null,null,Instant.parse("2027-01-31T12:00:00Z"),Instant.parse("2027-03-01T12:00:00Z"))
    val details = DetailsUiState(day,DetailsPhase.Content,SubscriptionDetails(row,
        LifecycleView(VisibleStatus.ACTIVE,LocalDate.of(2027,3,31),0.5)))
    val editor = EditorState(loading = false,fields = mapOf("name" to row.name,"label" to "Семейный","amount" to "799,00",
        "currency" to "RUB","unit" to "MONTH","count" to "1","mode" to "AUTO","series" to "false","date" to "2027-03-31",
        "anchorMonth" to "2027-01","anchorDay" to "31","start" to "2027-03-01","end" to ""))
}

@Composable internal fun DetailsDemo(state: DetailsUiState = DetailsEditorPreviewData.details,theme: Theme = Theme.SYSTEM,
    onBack: () -> Unit = {},onEdit: () -> Unit = {},onAction: (DetailsAction) -> Unit = {},onRefresh: () -> Unit = {}) {
    TrackerTheme(theme) {
        Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            DetailsPane(state,DetailsEditorPreviewData.id,onBack,onEdit,onBack,onAction,onRefresh)
        } }
    }
}

@Composable internal fun EditorDemo(state: EditorState = DetailsEditorPreviewData.editor, theme: Theme = Theme.SYSTEM,
    editing: Boolean = true,onChange: (String,String) -> Unit = {_,_ ->},onSave: () -> Unit = {},onExit: () -> Unit = {}) {
    TrackerTheme(theme) {
        Scaffold(topBar = { EditorHeader(stringResource(if(editing) R.string.editor_edit_title else R.string.editor_title),onExit) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                EditorForm(state,onChange,onSave,{},{},onExit,{})
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
