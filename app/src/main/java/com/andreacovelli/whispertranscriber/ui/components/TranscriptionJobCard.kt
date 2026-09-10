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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
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
import com.andreacovelli.whispertranscriber.ui.main.TranscriptionJobState

@Composable
fun TranscriptionJobCard(job: TranscriptionJobState, canSummarize: Boolean, actions: TranscriptionJobCardActions) {
    val queuedProviderLabel = providerFamilyLabel(job.provider)
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
            JobHeader(job = job, onDelete = actions.delete)
            JobSecondaryText(job = job, queuedProviderLabel = queuedProviderLabel)
            Text(job.status, style = MaterialTheme.typography.bodySmall)
            JobErrorSection(job = job, onRetry = actions.retryTranscription)
            JobProgressSection(job = job)
            TranscriptSection(job = job, actions = actions)
            StopTranscriptionSection(job = job, actions = actions)
            JobPrimaryActions(job = job, canSummarize = canSummarize, actions = actions)
            SummaryErrorSection(job = job)
            SummarySection(job = job, actions = actions)
        }
    }
}

@Composable
private fun JobHeader(job: TranscriptionJobState, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = job.displayName.ifBlank { stringResource(R.string.job_audio_file) },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (!job.isRunning) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_job_description),
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
        append(stringResource(R.string.job_provider_label, queuedProviderLabel))
        if (job.language.isNotBlank()) {
            append(" • ")
            append(stringResource(R.string.job_language_label, job.language))
        }
        if (job.model.isNotBlank()) {
            append(" • ")
            append(stringResource(R.string.job_model_label, job.model))
        } else if (job.requestedModel.isNotBlank()) {
            append(" • ")
            append(stringResource(R.string.job_queued_model_label, job.requestedModel))
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
        OutlinedButton(onClick = onRetry, enabled = job.canRetry) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.retry_description),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.retry))
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
    CardSectionHeader(
        title = stringResource(R.string.job_transcription_title),
        onCopy = actions.copyTranscript,
        onShare = actions.shareTranscript,
        onClear = actions.clearTranscript,
        shareDescription = stringResource(R.string.share_transcription_title),
        clearDescription = stringResource(R.string.clear_transcription),
    )
    ExpandableText(
        text = job.transcript,
        modifier = Modifier.fillMaxWidth(),
        minimizedMaxLines = 6,
    )
}

@Composable
private fun StopTranscriptionSection(job: TranscriptionJobState, actions: TranscriptionJobCardActions) {
    if (!job.shouldShowStopAction()) return
    Button(onClick = actions.cancelTranscription) {
        Text(stringResource(R.string.stop_transcription))
    }
}

internal fun TranscriptionJobState.shouldShowStopAction(): Boolean = isRunning

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
            Text(stringResource(R.string.summarize))
        }
    }
}

@Composable
private fun SummaryErrorSection(job: TranscriptionJobState) {
    val summaryError = job.summaryError ?: return
    if (summaryError.isBlank()) return
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.job_summarization_error, summaryError),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SummarySection(job: TranscriptionJobState, actions: TranscriptionJobCardActions) {
    if (job.summary.isBlank() && !job.isSummarizing) return
    Spacer(Modifier.height(12.dp))
    CardSectionHeader(
        title = stringResource(R.string.job_summary_title),
        onCopy = actions.copySummary,
        onShare = actions.shareSummary,
        onClear = actions.clearSummary,
        shareDescription = stringResource(R.string.share_summary_title),
        clearDescription = stringResource(R.string.clear_summary),
        trailing = if (job.isSummarizing) {
            {
                TextButton(onClick = actions.cancelSummary) {
                    Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            null
        },
    )
    if (job.summaryModel.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        val providerLabel = providerFamilyLabel(job.summaryProvider, job.summaryModel)
        Text(
            text = stringResource(R.string.job_summary_provider_model, providerLabel, job.summaryModel),
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
        ExpandableText(
            text = job.summary,
            modifier = Modifier.fillMaxWidth(),
            minimizedMaxLines = 6,
        )
    }
    if (!job.isSummarizing) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = actions.retrySummary,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.resummarize_description),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.resummarize))
            }
        }
    }
}
