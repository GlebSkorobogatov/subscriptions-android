package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import com.glebskorobogatov.subscriptiontracker.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun TrackerPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    loading: Boolean = false) {
    val pending = stringResource(R.string.tracker_loading)
    Button(onClick, modifier.heightIn(min = TrackerDimensions.buttonHeight).semantics {
        if(loading) stateDescription = pending
    }, enabled = enabled && !loading, colors = trackerButtonColors(),shape = TrackerShapes.capsule) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),verticalAlignment = Alignment.CenterVertically) {
            if(loading) CircularProgressIndicator(Modifier.size(20.dp),color = TrackerDesign.colors.textSecondary,strokeWidth = 2.dp)
            Text(text)
        }
    }
}

@Composable
fun TrackerSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilledTonalButton(onClick, modifier.heightIn(min = TrackerDimensions.minimumTouchTarget), enabled = enabled,
        shape = TrackerShapes.capsule,colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = TrackerDesign.colors.surfaceSubtle,contentColor = TrackerDesign.colors.textPrimary,
            disabledContainerColor = TrackerDesign.colors.surfaceSubtle,disabledContentColor = TrackerDesign.colors.textSecondary)) { Text(text) }
}

@Composable
fun TrackerSummaryCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = TrackerShapes.card, color = TrackerDesign.colors.surfaceSubtle, tonalElevation = 0.dp) {
        Column(Modifier.padding(TrackerDimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(TrackerDimensions.itemSpacing), content = content)
    }
}

/** A content-sized list row; the entire row is one accessible target when clickable. */
@Composable
fun TrackerListRow(title: String, modifier: Modifier = Modifier, supportingText: String? = null,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null) {
    ListItem(headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = supportingText?.let { { Text(it) } }, leadingContent = leading, trailingContent = trailing,
        modifier = modifier.fillMaxWidth().heightIn(min = TrackerDimensions.minimumTouchTarget)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
}

@Composable
fun TrackerTextField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, errorMessage: String? = null, enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, inputModifier: Modifier = Modifier,
    readOnly: Boolean = false, supportingModifier: Modifier = Modifier,
    supportingText: String? = null, keyboardActions: KeyboardActions = KeyboardActions.Default,
    suffix: (@Composable () -> Unit)? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
        OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth().then(inputModifier).semantics {
            contentDescription = label
            if (errorMessage != null) error(errorMessage)
        }, enabled = enabled, isError = errorMessage != null, shape = TrackerShapes.field,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = TrackerDesign.colors.surfaceSubtle,unfocusedContainerColor = TrackerDesign.colors.surfaceSubtle,
                disabledContainerColor = TrackerDesign.colors.surfaceSubtle,errorContainerColor = TrackerDesign.colors.surfaceSubtle,
                focusedTextColor = TrackerDesign.colors.textPrimary,unfocusedTextColor = TrackerDesign.colors.textPrimary,
                disabledTextColor = TrackerDesign.colors.textSecondary,errorTextColor = TrackerDesign.colors.textPrimary,
                focusedBorderColor = TrackerDesign.colors.textSecondary,unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent,errorBorderColor = TrackerDesign.colors.error,
                errorSupportingTextColor = TrackerDesign.colors.error,unfocusedSupportingTextColor = TrackerDesign.colors.textSecondary,
                disabledSupportingTextColor = TrackerDesign.colors.textSecondary),
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, readOnly = readOnly,
            suffix = suffix,
            supportingText = (errorMessage ?: supportingText)?.let { { Text(it, supportingModifier) } })
    }
}

/** Dialog text scrolls on small/landscape displays; Material supplies focus and Back handling. */
@Composable
fun TrackerConfirmationDialog(title: String, message: String, confirmText: String, dismissText: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false, busy: Boolean = false, tag: String? = null) {
    val scrollTitle = scrollDialogHeading()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, modifier = Modifier.semantics { paneTitle = title }
        .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        title = if(scrollTitle) null else ({ Text(title,style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }) }),
        text = { Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(scrollTitle) TrackerDialogHeading(title)
            Text(message)
        } },
        confirmButton = {
            TrackerTextButton(confirmText,onConfirm,enabled = !busy,destructive = destructive)
        }, dismissButton = { TrackerTextButton(dismissText, onDismiss, enabled = !busy) },
        shape = TrackerShapes.card, containerColor = TrackerDesign.colors.surfaceRaised, tonalElevation = 0.dp)
}
