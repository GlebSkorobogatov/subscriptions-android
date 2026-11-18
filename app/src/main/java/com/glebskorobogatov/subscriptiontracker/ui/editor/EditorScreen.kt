package com.glebskorobogatov.subscriptiontracker.ui.editor

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.time.LocalDate
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, exitRequest: Int, userBackRequest: Int = 0,
    liveNotificationSequence: Long? = null, boundExitSequence: Long? = null,
    onSaved: (java.util.UUID) -> Unit = {}, onExitArmed: (Long?) -> Unit = {},
    onExitCancelled: () -> Unit = {}, onExitAbandoned: () -> Unit = {}, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var catalogOpen by rememberSaveable { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.selectImage(uri)
    }
    fun applyBack(effects: List<EditorBackEffect>) {
        for (effect in effects) when (effect) {
            EditorBackEffect.CloseSheet -> { picker = null; catalogOpen = false }
            EditorBackEffect.ConfirmDirty -> {
                // Arm once, with the sequence captured when the exit was requested.
                if (!confirm) onExitArmed(liveNotificationSequence)
                confirm = true
            }
            EditorBackEffect.Leave -> { onExitAbandoned(); onBack() }
            EditorBackEffect.Ignore -> Unit
        }
    }
    fun backEffects(external: Boolean) = editorBackEffects(
        sheetOpen = picker != null || catalogOpen, external = external, saving = state.saving, dirty = state.dirty)
    BackHandler(enabled = picker == null && !catalogOpen) { applyBack(backEffects(external = false)) }
    var lastUserBack by remember { mutableIntStateOf(userBackRequest) }
    LaunchedEffect(userBackRequest) {
        if (lastUserBack == userBackRequest) return@LaunchedEffect
        lastUserBack = userBackRequest
        applyBack(backEffects(external = false))
    }
    var lastExit by remember { mutableIntStateOf(exitRequest) }
    LaunchedEffect(exitRequest, state.saving, state.done) {
        when (resolveEditorExit(exitRequest, lastExit, state.saving, state.done)) {
            EditorExitResolution.AlreadyHandled, EditorExitResolution.Pending -> Unit
            EditorExitResolution.SavedOwnsExit -> lastExit = exitRequest
            EditorExitResolution.Leave -> {
                lastExit = exitRequest
                applyBack(backEffects(external = true))
            }
        }
    }
    LaunchedEffect(state.done) { if (state.done) { onSaved(vm.savedSubscriptionId); onBack() } }
    if (confirm) TrackerConfirmationDialog(
        title = stringResource(R.string.editor_unsaved), message = stringResource(R.string.editor_unsaved_explanation),
        confirmText = stringResource(R.string.editor_discard), dismissText = stringResource(R.string.editor_continue),
        onConfirm = { vm.discardImageDraft(); onExitAbandoned(); onBack() },
        onDismiss = { confirm = false; onExitCancelled() },
        tag = "editor_exit_${boundExitSequence?.toString() ?: "unbound"}_live_${liveNotificationSequence?.toString() ?: "none"}")
    EditorForm(state, vm::change, vm::save, { vm.reload(false) }, { vm.reload() }, { applyBack(backEffects(external = false)) }, { picker = it },
        onRemoveImage = vm::removeImage, onPickCatalog = { catalogOpen = true }, onChangeFrequency = vm::changeFrequency,
        onFrequencyMode = vm::setFrequencyMode)
    if (catalogOpen) CatalogPickerDialog(state.catalogLogoId, { catalogOpen = false }, { catalogId ->
        vm.selectCatalog(catalogId)
        catalogOpen = false
    }, onPickImage = {
        catalogOpen = false
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    })
    picker?.let { key ->
        EditorDateSheet(initialDateMillis(state.fields[key]), { picker = null }) { millis ->
            vm.change(key, epochDayDate(millis).toString())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("AppBundleLocaleChanges")
@Composable
internal fun EditorDateSheet(initialMillis: Long?, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    EditorDatePicker(initialMillis, onDismiss, onConfirm)
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("AppBundleLocaleChanges")
@Composable
private fun EditorDatePicker(initialMillis: Long?, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    // The sheet window replaces context and configuration locals. Apply Russian inside that content,
    // after the window locals, so Material strings and the date model both follow the app language.
    // The process and device locales stay unchanged. Russian resources are already in the base module.
    val base = LocalContext.current
    val current = LocalConfiguration.current
    val locale = Locale.forLanguageTag("ru")
    fun russianConfiguration() = Configuration(current).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    val russian = remember(base, current.fontScale, current.densityDpi, current.orientation, current.uiMode) {
        base.createConfigurationContext(russianConfiguration())
    }
    val configuration = remember(current.fontScale, current.densityDpi, current.orientation, current.uiMode) {
        russianConfiguration()
    }
    val colors = TrackerDesign.colors
    val pickerState = remember { mutableStateOf<DatePickerState?>(null) }
    TrackerBottomSheet(onDismiss, Modifier.testTag("editor_date_sheet"), skipPartiallyExpanded = true, footer = {
        TrackerPrimaryButton(stringResource(R.string.editor_choose), {
            pickerState.value?.selectedDateMillis?.let(onConfirm)
            onDismiss()
        }, Modifier.fillMaxWidth().testTag("editor_date_confirm"))
    }) {
        CompositionLocalProvider(
            LocalContext provides russian,
            LocalResources provides russian.resources,
            LocalConfiguration provides configuration,
        ) {
            val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
            SideEffect { pickerState.value = dateState }
            Text(stringResource(R.string.editor_pick_date), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            DatePicker(dateState, title = null, colors = DatePickerDefaults.colors(
            containerColor = colors.surfaceRaised,
            titleContentColor = colors.textPrimary,
            headlineContentColor = colors.textPrimary,
            weekdayContentColor = colors.textSecondary,
            subheadContentColor = colors.textSecondary,
            navigationContentColor = colors.textPrimary,
            yearContentColor = colors.textPrimary,
            disabledYearContentColor = colors.textSecondary,
            currentYearContentColor = colors.textPrimary,
            selectedYearContentColor = colors.primaryButtonText,
            disabledSelectedYearContentColor = colors.textSecondary,
            selectedYearContainerColor = colors.primaryButtonBg,
            disabledSelectedYearContainerColor = colors.surfaceSubtle,
            dayContentColor = colors.textPrimary,
            disabledDayContentColor = colors.textSecondary,
            selectedDayContentColor = colors.primaryButtonText,
            disabledSelectedDayContentColor = colors.textSecondary,
            selectedDayContainerColor = colors.primaryButtonBg,
            disabledSelectedDayContainerColor = colors.surfaceSubtle,
            todayContentColor = colors.textPrimary,
            todayDateBorderColor = colors.textSecondary,
            dayInSelectionRangeContentColor = colors.textPrimary,
            dayInSelectionRangeContainerColor = colors.surfaceSubtle,
            dividerColor = colors.divider,
            ))
        }
    }
}

/** Material DatePicker's documented UTC milliseconds encode a calendar date only. */
internal fun initialDateMillis(value: String?): Long? =
    runCatching { LocalDate.parse(value).takeIf { it.year in 1900..2100 }?.toEpochDay()?.times(86_400_000L) }.getOrNull()

internal fun epochDayDate(millis: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(millis, 86_400_000L))

/** Presentation adapter: all validation, persistence and schedule commands stay in EditorViewModel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorForm(state: EditorState, onChange: (String, String) -> Unit, onSave: () -> Unit,
    onRetry: () -> Unit, onReload: () -> Unit, onExit: () -> Unit, onPickDate: (String) -> Unit,
    onRemoveImage: () -> Unit = {}, onPickCatalog: () -> Unit = {},
    onChangeFrequency: (String, String) -> Unit = { unit, count -> onChange("unit", unit); onChange("count", count) },
    onFrequencyMode: (Boolean) -> Unit = {}) {
    val horizontal = trackerPageHorizontalPadding()
    val blocked = state.saving || state.importingImage || state.loading || state.conflict ||
        state.loadFailure || state.notFound || state.done
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))) {
        Column(Modifier.fillMaxSize().testTag("subscription_editor")) {
            if (state.loading) {
                Row(Modifier.padding(horizontal), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.editor_loading))
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontal)
                    .padding(top = TrackerDimensions.contentSpacing, bottom = TrackerDimensions.sectionSpacing),
                    verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
                    state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (state.loadFailure) TrackerPrimaryButton(stringResource(R.string.retry_loading), onRetry, Modifier.fillMaxWidth())
                    if (state.notFound) {
                        Text(stringResource(R.string.subscription_not_found))
                        TrackerSecondaryButton(stringResource(R.string.navigate_back), onExit)
                    } else {
                        if (state.conflict) TrackerPrimaryButton(stringResource(R.string.editor_reload), onReload, Modifier.fillMaxWidth())
                        fun value(key: String) = state.fields[key].orEmpty()
                        val enabled = !state.saving && !state.importingImage && !state.loadFailure
                        fun editable(key: String) = enabled && (state.scheduleEditable || key !in setOf("count", "date", "anchorDay", "start"))
                        @Composable fun field(key: String, label: Int) {
                            TrackerTextField(value(key), { onChange(key, it) }, stringResource(label),
                                enabled = editable(key), errorMessage = state.errors[key]?.let { stringResource(it) },
                                inputModifier = Modifier.testTag("field_$key"),
                                keyboardOptions = KeyboardOptions(keyboardType = when (key) {
                                    "amount" -> KeyboardType.Decimal; "count", "anchorDay" -> KeyboardType.Number; else -> KeyboardType.Text
                                }),
                                suffix = if (key == "amount") {{ Text(stringResource(R.string.editor_ruble_suffix)) }} else null)
                        }
                        @Composable fun date(key: String, label: Int) {
                            field(key, label)
                            val description = stringResource(R.string.editor_pick_date_for, stringResource(label))
                            TrackerSecondaryButton(stringResource(R.string.editor_pick_date), { onPickDate(key) },
                                Modifier.testTag("date_picker_$key").semantics { contentDescription = description }, enabled = editable(key))
                        }
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
                            Box(Modifier.testTag("editor_icon"), contentAlignment = Alignment.Center) {
                                // The design system defines one icon size. The editor centers that 54 dp mark above the fields.
                                SubscriptionImage(value("name"), if (state.imageRemoved || state.catalogLogoId != null) null else state.logoAssetId,
                                    size = TrackerDimensions.logoSize, pendingId = state.pendingImageId, catalogId = state.catalogLogoId)
                            }
                            TrackerSecondaryButton(stringResource(R.string.editor_choose_catalog), onPickCatalog,
                                Modifier.testTag("editor_choose_catalog"), enabled = enabled)
                            if ((state.logoAssetId != null && !state.imageRemoved) || state.pendingImageId != null || state.catalogLogoId != null) {
                                TrackerSecondaryButton(stringResource(R.string.editor_remove_image), onRemoveImage, enabled = enabled)
                            }
                        }
                        field("name", R.string.editor_name)
                        field("label", R.string.editor_label)
                        field("amount", R.string.editor_amount)
                        Text(stringResource(R.string.editor_frequency), style = MaterialTheme.typography.labelLarge)
                        val preset = editorFrequencySelection(value("unit"), value("count"), state.customFrequency)
                        val scheduleEnabled = enabled && state.scheduleEditable
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing),
                            verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
                            EditorPeriodPreset.entries.forEach { choice ->
                                TrackerSelectedCapsule(stringResource(choice.label), preset == choice, {
                                    if (choice == EditorPeriodPreset.CUSTOM) {
                                        if (preset != EditorPeriodPreset.CUSTOM) {
                                            onFrequencyMode(true)
                                            onChangeFrequency(choice.unit, choice.count)
                                        }
                                    } else {
                                        onFrequencyMode(false)
                                        onChangeFrequency(choice.unit, choice.count)
                                    }
                                }, Modifier.testTag(choice.tag), enabled = scheduleEnabled)
                            }
                        }
                        if (preset == EditorPeriodPreset.CUSTOM) {
                            TrackerTextField(value("count"), {
                                onFrequencyMode(true)
                                onChange("count", it)
                            }, stringResource(R.string.editor_custom_count), enabled = editable("count"),
                                errorMessage = state.errors["count"]?.let { stringResource(it) },
                                inputModifier = Modifier.testTag("field_count"),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing),
                                verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing)) {
                                listOf("DAY" to R.string.editor_unit_day, "WEEK" to R.string.editor_unit_week,
                                    "MONTH" to R.string.editor_unit_month, "YEAR" to R.string.editor_unit_year).forEach { (unit, label) ->
                                    TrackerSelectedCapsule(stringResource(label), value("unit") == unit, {
                                        onFrequencyMode(true)
                                        onChange("unit", unit)
                                    }, Modifier.testTag("frequency_unit_$unit"), enabled = scheduleEnabled)
                                }
                            }
                        }
                        date("date", R.string.editor_date)
                        if (state.fields["displayedDue"] == "" && value("date").isBlank())
                            Text(stringResource(R.string.projection_calendar_error), color = TrackerDesign.colors.error)
                        if (!state.scheduleEditable) Text(stringResource(R.string.editor_restore_schedule), color = TrackerDesign.colors.textSecondary)
                        Text(stringResource(R.string.editor_series), color = TrackerDesign.colors.textSecondary)
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        val open = editorExtraVisible(expanded, state.errors)
                        val expandedState = stringResource(if (open) R.string.ui_expanded else R.string.ui_collapsed)
                        TrackerSecondaryButton(stringResource(R.string.editor_extra), { expanded = !expanded },
                            Modifier.testTag("editor_extra").semantics { stateDescription = expandedState })
                        if (open) {
                            field("anchorDay", R.string.editor_anchor_day)
                            date("start", R.string.editor_start)
                        }
                    }
                }
                if (!state.notFound) {
                    TrackerPrimaryButton(stringResource(if (state.saving) R.string.editor_saving else R.string.editor_save), onSave,
                        Modifier.fillMaxWidth().padding(horizontal = horizontal).padding(bottom = TrackerDimensions.itemSpacing)
                            .testTag("editor_save"),
                        enabled = !blocked, loading = state.saving)
                }
            }
        }
    }
}
