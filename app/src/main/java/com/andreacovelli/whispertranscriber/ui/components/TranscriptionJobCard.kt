package com.andreacovelli.whispertranscriber.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.TranscriptionJobState

@Composable
fun TranscriptionJobCard(
    job: TranscriptionJobState,
    canSummarize: Boolean,
    onSummarize: () -> Unit,
    onClearSummary: () -> Unit,
    onClearTranscript: () -> Unit,
    onCopyTranscript: () -> Unit,
    onCopySummary: () -> Unit,
    onShareTranscript: () -> Unit,
    onShareSummary: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val queuedProviderLabel = providerDisplayLabel(job.provider, job.requestedModel)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = job.displayName.ifBlank { "Audio file" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (job.isRunning || job.status == "Queued") {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete job",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            val secondary = buildString {
                append("Provider: $queuedProviderLabel")
                if (job.language.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append("Language: ${job.language}")
                }
                if (job.model.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append("Model: ${job.model}")
                } else if (job.requestedModel.isNotBlank() && job.provider != com.andreacovelli.whispertranscriber.TranscriptionProvider.ELEVEN_LABS) {
                    if (isNotEmpty()) append(" • ")
                    append("Queued model: ${job.requestedModel}")
                }
            }
            if (secondary.isNotBlank()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }

            Text(job.status, style = MaterialTheme.typography.bodySmall)

            if (job.error != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = job.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry")
                    }
                }
            }

            if (job.transcript.isBlank() && job.error == null && job.isRunning) {
                Spacer(Modifier.height(8.dp))
                if (job.status.contains("Converting")) {
                    LinearProgressIndicator(
                        progress = { job.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (job.transcript.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Transcription", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCopyTranscript) {
                            Icon(Icons.Filled.ContentCopy, "Copy Transcription")
                        }
                        IconButton(onClick = onShareTranscript) {
                            Icon(Icons.Default.Share, "Share Transcription")
                        }
                        IconButton(onClick = onClearTranscript) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.clear_transcription),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                ExpandableText(
                    text = job.transcript,
                    modifier = Modifier.fillMaxWidth(),
                    minimizedMaxLines = 6
                )
            }

            if (!job.isRunning && job.error == null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = if (job.transcript.isNotBlank()) Modifier.padding(end = 8.dp) else Modifier
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Redo", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Redo")
                    }
                    if (job.transcript.isNotBlank()) {
                        Button(
                            onClick = onSummarize,
                            enabled = canSummarize && !job.isSummarizing,
                        ) {
                            Text(
                                when {
                                    job.isSummarizing -> "Summarizing..."
                                    job.summary.isNotBlank() -> "Re-summarize"
                                    else -> "Summarize with Gemini"
                                }
                            )
                        }
                    }
                }
            }

            if (!job.summaryError.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Summarization error: ${job.summaryError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (job.summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Summary", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCopySummary) {
                            Icon(Icons.Filled.ContentCopy, "Copy Summary")
                        }
                        IconButton(onClick = onShareSummary) {
                            Icon(Icons.Default.Share, "Share Summary")
                        }
                        IconButton(onClick = onClearSummary) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.clear_summary),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                SelectionContainer {
                    Text(job.summary)
                }
            }
        }
    }
}
