package com.andreacovelli.whispertranscriber.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.andreacovelli.whispertranscriber.R

const val TRANSCRIPT_EDIT_FIELD_TAG = "transcript_edit_field"

/** Edits [text] in place; tapping outside does not dismiss, so a long correction is not lost by accident. */
@Composable
fun TranscriptEditDialog(text: TextFieldState, hasSummary: Boolean, onSave: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp),
        title = { Text(stringResource(R.string.history_edit_transcript)) },
        text = {
            Column {
                OutlinedTextField(
                    state = text,
                    label = { Text(stringResource(R.string.history_edit_transcript_label)) },
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 480.dp)
                        .testTag(TRANSCRIPT_EDIT_FIELD_TAG),
                )
                if (hasSummary) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.history_edit_transcript_summary_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = text.text.isNotBlank()) {
                Text(stringResource(R.string.history_edit_transcript_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
