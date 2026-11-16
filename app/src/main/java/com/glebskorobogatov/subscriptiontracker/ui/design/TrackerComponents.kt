package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun TrackerPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.heightIn(min = TrackerDimensions.buttonHeight), enabled = enabled,
        shape = TrackerShapes.capsule) { Text(text) }
}

@Composable
fun TrackerSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick, modifier.heightIn(min = TrackerDimensions.minimumTouchTarget), enabled = enabled) { Text(text) }
}

@Composable
fun TrackerSummaryCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = TrackerShapes.card, color = TrackerDesign.colors.surface, tonalElevation = 0.dp) {
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
    readOnly: Boolean = false, supportingModifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
        OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth().then(inputModifier).semantics {
            contentDescription = label
            if (errorMessage != null) error(errorMessage)
        }, enabled = enabled, isError = errorMessage != null, shape = TrackerShapes.field,
            keyboardOptions = keyboardOptions, readOnly = readOnly,
            supportingText = errorMessage?.let { { Text(it, supportingModifier) } })
    }
}

/** Dialog text scrolls on small/landscape displays; Material supplies focus and Back handling. */
@Composable
fun TrackerConfirmationDialog(title: String, message: String, confirmText: String, dismissText: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false, busy: Boolean = false) {
    val scrollTitle = scrollDialogHeading()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },modifier = Modifier.semantics { paneTitle = title },
        title = if(scrollTitle) null else ({ Text(title) }),
        text = { Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(scrollTitle) TrackerDialogHeading(title)
            Text(message)
        } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                Text(confirmText)
            }
        }, dismissButton = { TrackerSecondaryButton(dismissText, onDismiss, enabled = !busy) },
        shape = TrackerShapes.card, containerColor = TrackerDesign.colors.surfaceRaised, tonalElevation = 0.dp)
}
