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
import androidx.compose.material3.ButtonDefaults
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
fun TranscriptionJobCard(job: TranscriptionJobState, canSummarize: Boolean, actions: TranscriptionJobCardActions) {
    val queuedProviderLabel = providerDisplayLabel(job.provider, job.requestedModel)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ),
        ) {
            JobHeader(job = job, onCancel = actions.cancel, onDelete = actions.delete)
            JobSecondaryText(job = job, queuedProviderLabel = queuedProviderLabel)
            Text(job.status, style = MaterialTheme.typography.bodySmall)
            JobErrorSection(job = job, onRetry = actions.retryTranscription)
            JobProgressSection(job = job)
            TranscriptSection(job = job, actions = actions)
            JobPrimaryActions(job = job, canSummarize = canSummarize, actions = actions)
            SummaryErrorSection(job = job)
            SummarySection(job = job, actions = actions)
        }
    }
}

@Composable
private fun JobHeader(job: TranscriptionJobState, onCancel: () -> Unit, onDelete: () -> Unit) {
    val showHeaderCancel = job.status == "Queued" || (job.isRunning && job.transcript.isBlank())
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = job.displayName.ifBlank { "Audio file" },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (showHeaderCancel) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = MaterialTheme.colorScheme.error)
            }
        } else if (!job.isRunning) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete job",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun JobSecondaryText(job: TranscriptionJobState, queuedProviderLabel: String) {
    val secondary = buildString {
        append("Provider: $queuedProviderLabel")
        if (job.language.isNotBlank()) {
            append(" • Language: ${job.language}")
        }
        if (job.model.isNotBlank()) {
            append(" • Model: ${job.model}")
        } else if (job.requestedModel.isNotBlank() &&
            job.provider != com.andreacovelli.whispertranscriber.TranscriptionProvider.ELEVEN_LABS
        ) {
            append(" • Queued model: ${job.requestedModel}")
        }
    }
    if (secondary.isBlank()) return
    Text(
        text = secondary,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun JobErrorSection(job: TranscriptionJobState, onRetry: () -> Unit) {
    val error = job.error ?: return
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Retry")
        }
    }
}

@Composable
private fun JobProgressSection(job: TranscriptionJobState) {
    if (job.transcript.isNotBlank() || job.error != null || !job.isRunning) return
    Spacer(Modifier.height(8.dp))
    if (job.status.contains("Converting")) {
        LinearProgressIndicator(
            progress = { job.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TranscriptSection(job: TranscriptionJobState, actions: TranscriptionJobCardActions) {
    if (job.transcript.isBlank()) return
    Spacer(Modifier.height(12.dp))
    SectionHeader(
        title = "Transcription",
        onCopy = actions.copyTranscript,
        onShare = actions.shareTranscript,
        onClear = actions.clearTranscript,
        clearDescription = stringResource(R.string.clear_transcription),
    )
    ExpandableText(
        text = job.transcript,
        modifier = Modifier.fillMaxWidth(),
        minimizedMaxLines = 6,
    )
    Spacer(Modifier.height(8.dp))
    val isRunning = job.isRunning
    Button(
        onClick = if (isRunning) actions.cancelTranscription else actions.retryTranscription,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (isRunning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        if (!isRunning) {
            Icon(Icons.Default.Refresh, contentDescription = "Retranscribe", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(if (isRunning) "Stop transcription" else "Retranscribe")
    }
}

@Composable
private fun JobPrimaryActions(
    job: TranscriptionJobState,
    canSummarize: Boolean,
    actions: TranscriptionJobCardActions,
) {
    if (job.isRunning || job.isSummarizing || job.error != null || job.transcript.isBlank() ||
        job.summary.isNotBlank()
    ) {
        return
    }
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Button(
            onClick = actions.summarize,
            enabled = canSummarize,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Text("Summarize")
        }
    }
}

@Composable
private fun SummaryErrorSection(job: TranscriptionJobState) {
    val summaryError = job.summaryError ?: return
    if (summaryError.isBlank()) return
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Summarization error: $summaryError",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SummarySection(job: TranscriptionJobState, actions: TranscriptionJobCardActions) {
    if (job.summary.isBlank() && !job.isSummarizing) return
    Spacer(Modifier.height(12.dp))
    SectionHeader(
        title = "Summary",
        onCopy = actions.copySummary,
        onShare = actions.shareSummary,
        onClear = actions.clearSummary,
        clearDescription = stringResource(R.string.clear_summary),
        trailing = if (job.isSummarizing) {
            {
                TextButton(onClick = actions.cancelSummary) {
                    Text("Cancel", color = MaterialTheme.colorScheme.error)
                }
            }
        } else null,
    )
    if (job.summaryModel.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Provider: ${job.summaryModel}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (job.isSummarizing || job.summaryStatus.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = job.summaryStatus,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (job.isSummarizing) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
    if (job.summary.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(job.summary)
        }
    }
    if (!job.isSummarizing) {
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = actions.retrySummary,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Resummarize", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Resummarize")
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    clearDescription: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (trailing != null) {
            trailing()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, "Copy $title")
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, "Share $title")
                }
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = clearDescription,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
