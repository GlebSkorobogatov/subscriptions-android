package com.glebskorobogatov.subscriptiontracker.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glebskorobogatov.subscriptiontracker.R
import java.time.LocalDate
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.Alignment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, exitRequest: Int, onSaved: (java.util.UUID) -> Unit = {}, onExitCancelled: () -> Unit = {}, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var catalogOpen by rememberSaveable { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.selectImage(uri)
    }
    fun leave() { if (!state.saving) { if (state.dirty) confirm = true else onBack() } }
    BackHandler { if (catalogOpen) catalogOpen = false else leave() }
    var lastExit by remember { mutableIntStateOf(exitRequest) }
    LaunchedEffect(exitRequest) { if (exitRequest != lastExit) { lastExit = exitRequest; leave() } }
    LaunchedEffect(state.done) { if (state.done) { onSaved(vm.savedSubscriptionId); onBack() } }
    if (confirm) TrackerConfirmationDialog(
        title = stringResource(R.string.editor_unsaved), message = stringResource(R.string.editor_unsaved_explanation),
        confirmText = stringResource(R.string.editor_discard), dismissText = stringResource(R.string.editor_continue),
        onConfirm = { vm.discardImageDraft(); onBack() }, onDismiss = { confirm = false; onExitCancelled() })
    EditorForm(state, vm::change, vm::save, { vm.reload(false) }, { vm.reload() }, { leave() }, { picker = it },
        onRemoveImage = vm::removeImage, onPickCatalog = { catalogOpen = true })
    if (catalogOpen) CatalogPickerDialog(state.catalogLogoId, { catalogOpen = false }, { catalogId ->
        vm.selectCatalog(catalogId)
        catalogOpen = false
    }, onPickImage = {
        catalogOpen = false
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    })
    picker?.let { key ->
        // Material DatePicker's documented UTC milliseconds encode a calendar date only.
        // Epoch-day arithmetic avoids device timezone conversion and date shifts.
        val initial = runCatching { LocalDate.parse(state.fields[key]).takeIf { it.year in 1900..2100 }?.toEpochDay()?.times(86_400_000L) }.getOrNull()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(onDismissRequest = { picker = null }, confirmButton = {
            TextButton(onClick = { dateState.selectedDateMillis?.let { vm.change(key,LocalDate.ofEpochDay(Math.floorDiv(it,86_400_000L)).toString()) }; picker = null }) {
                Text(stringResource(R.string.editor_choose))
            }
        }) { DatePicker(dateState) }
    }
}

/** Presentation adapter: all validation, persistence and schedule commands stay in EditorViewModel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorForm(state: EditorState, onChange: (String, String) -> Unit, onSave: () -> Unit,
    onRetry: () -> Unit, onReload: () -> Unit, onExit: () -> Unit, onPickDate: (String) -> Unit,
    onRemoveImage: () -> Unit = {}, onPickCatalog: () -> Unit = {}) {
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(TrackerDimensions.screenPadding).testTag("subscription_editor"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.editor_loading))
                }
                return@Column
            }
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (state.loadFailure) TrackerPrimaryButton(stringResource(R.string.retry_loading), onRetry)
            if (state.notFound) {
                Text(stringResource(R.string.subscription_not_found))
                TrackerSecondaryButton(stringResource(R.string.navigate_back), onExit)
                return@Column
            }
            if (state.conflict) TrackerPrimaryButton(stringResource(R.string.editor_reload), onReload)
            fun value(key: String) = state.fields[key].orEmpty()
            val enabled = !state.saving && !state.importingImage && !state.loadFailure
            fun editable(key: String) = enabled && (state.scheduleEditable || key !in setOf("count","date","anchorDay","start"))
            @Composable fun field(key: String, label: Int) {
                TrackerTextField(value(key), { onChange(key, it) }, stringResource(label),
                    enabled = editable(key), errorMessage = state.errors[key]?.let { stringResource(it) },
                    inputModifier = Modifier.testTag("field_$key"),
                    keyboardOptions = KeyboardOptions(keyboardType = when (key) {
                        "amount" -> KeyboardType.Decimal; "count", "anchorDay" -> KeyboardType.Number; else -> KeyboardType.Text
                    }))
            }
            @Composable fun date(key: String, label: Int) {
                field(key, label)
                val description = stringResource(R.string.editor_pick_date_for,stringResource(label))
                TrackerSecondaryButton(stringResource(R.string.editor_pick_date), { onPickDate(key) },
                    Modifier.testTag("date_picker_$key").semantics { contentDescription = description },enabled = editable(key))
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                SubscriptionImage(value("name"), if (state.imageRemoved || state.catalogLogoId != null) null else state.logoAssetId,
                    pendingId = state.pendingImageId, catalogId = state.catalogLogoId)
                TrackerSecondaryButton(stringResource(R.string.editor_choose_catalog), onPickCatalog,
                    Modifier.testTag("editor_choose_catalog"), enabled = enabled)
                if ((state.logoAssetId != null && !state.imageRemoved) || state.pendingImageId != null || state.catalogLogoId != null) {
                    TrackerSecondaryButton(stringResource(R.string.editor_remove_image), onRemoveImage, enabled = enabled)
                }
            }
            field("name", R.string.editor_name)
            field("label", R.string.editor_label)
            field("amount", R.string.editor_amount)
            Text(stringResource(R.string.editor_price_currency,"RUB"),style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = TrackerDesign.colors.divider)
            var frequencyMenu by remember { mutableStateOf(false) }
            Box {
                TrackerSecondaryButton(stringResource(R.string.editor_frequency), { frequencyMenu = true }, enabled = enabled && state.scheduleEditable)
                DropdownMenu(frequencyMenu, { frequencyMenu = false }) {
                    listOf(Triple(R.string.editor_week,"WEEK","1"), Triple(R.string.editor_month,"MONTH","1"),
                        Triple(R.string.editor_quarter,"MONTH","3"), Triple(R.string.editor_half,"MONTH","6"), Triple(R.string.editor_year,"YEAR","1"),
                        Triple(R.string.editor_custom,"DAY","1")).forEach { (label,unit,count) ->
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                            onChange("unit",unit); onChange("count",count); frequencyMenu = false
                        })
                    }
                }
            }
            field("count", R.string.editor_count)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("DAY" to R.string.editor_days,"WEEK" to R.string.editor_weeks,"MONTH" to R.string.editor_months,"YEAR" to R.string.editor_year).forEach { (unit,label) ->
                    FilterChip(value("unit") == unit, { onChange("unit",unit) }, label = { Text(stringResource(label)) }, enabled = enabled && state.scheduleEditable)
                }
            }
            date("date", R.string.editor_date)
            if (state.fields["displayedDue"] == "" && value("date").isBlank())
                Text(stringResource(R.string.projection_calendar_error),color = TrackerDesign.colors.error)
            if (!state.scheduleEditable) Text(stringResource(R.string.editor_restore_schedule), color = TrackerDesign.colors.textSecondary)
            Text(stringResource(R.string.editor_series), color = TrackerDesign.colors.textSecondary)
            var expanded by rememberSaveable { mutableStateOf(false) }
            HorizontalDivider(color = TrackerDesign.colors.divider)
            val expandedState = stringResource(if(expanded) R.string.ui_expanded else R.string.ui_collapsed)
            TrackerSecondaryButton(stringResource(R.string.editor_extra), { expanded = !expanded },
                Modifier.testTag("editor_extra").semantics { stateDescription = expandedState })
            if (expanded) {
                field("anchorDay", R.string.editor_anchor_day)
                date("start", R.string.editor_start)

            }
            TrackerPrimaryButton(stringResource(if(state.saving) R.string.editor_saving else R.string.editor_save), onSave,
                Modifier.fillMaxWidth(), enabled = !state.saving && !state.conflict && !state.loadFailure)
            TrackerSecondaryButton(stringResource(R.string.navigate_back), onExit)
        }
    }
}
