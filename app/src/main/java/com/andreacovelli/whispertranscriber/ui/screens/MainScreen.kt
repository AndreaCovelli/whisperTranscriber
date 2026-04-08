package com.andreacovelli.whispertranscriber.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreacovelli.whispertranscriber.ApiKeyState
import com.andreacovelli.whispertranscriber.MainViewModel
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.TranscriptionJobState
import com.andreacovelli.whispertranscriber.ui.components.TranscriptionJobCard
import com.andreacovelli.whispertranscriber.ui.components.TranscriptionJobCardActions
import com.andreacovelli.whispertranscriber.ui.components.providerDisplayLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onNavigateToHistory: () -> Unit, onNavigateToSettings: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = viewModel.mainScreenActions
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.main_title)) },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = stringResource(R.string.main_open_history))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.main_open_settings))
                    }
                },
            )
        },
    ) { paddingValues ->
        MainScreenContent(
            viewModel = viewModel,
            paddingValues = paddingValues,
            uiState = uiState,
            onNavigateToSettings = onNavigateToSettings,
            onClearCompleted = actions.clearCompletedJobs,
            clipboard = clipboard,
            scope = scope,
            context = context,
        )
    }
}

@Composable
private fun MainScreenContent(
    viewModel: MainViewModel,
    paddingValues: PaddingValues,
    uiState: com.andreacovelli.whispertranscriber.UiState,
    onNavigateToSettings: () -> Unit,
    onClearCompleted: () -> Unit,
    clipboard: Clipboard,
    scope: CoroutineScope,
    context: Context,
) {
    val transcriptionProviderLabel = providerDisplayLabel(uiState.provider, uiState.transcriptionCustomGeminiModelName)
    val summarizerProviderLabel =
        providerDisplayLabel(uiState.summarizerProvider, uiState.summarizerCustomGeminiModelName)
    LazyColumn(
        modifier = Modifier
            .padding(paddingValues)
            .fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ProviderSummaryCard(
                transcriptionProviderLabel = transcriptionProviderLabel,
                summarizerProviderLabel = summarizerProviderLabel,
                onNavigateToSettings = onNavigateToSettings,
            )
        }
        item { StatusCard(uiState.status) }
        if (uiState.batchProgressState != null) {
            item { BatchProgressCard(uiState.batchProgressState) }
        }
        if (uiState.jobs.isEmpty()) {
            item { EmptyJobsState() }
        } else {
            if (uiState.jobs.any { !it.isRunning && it.status != "Queued" }) {
                item { ClearCompletedRow(onClick = onClearCompleted) }
            }
            items(uiState.jobs, key = { it.id }) { job ->
                MainJobCard(
                    viewModel = viewModel,
                    job = job,
                    canSummarize = uiState.geminiApiKeyState is ApiKeyState.Present,
                    clipboard = clipboard,
                    scope = scope,
                    context = context,
                )
            }
        }
    }
}

@Composable
private fun MainJobCard(
    viewModel: MainViewModel,
    job: TranscriptionJobState,
    canSummarize: Boolean,
    clipboard: Clipboard,
    scope: CoroutineScope,
    context: Context,
) {
    TranscriptionJobCard(
        job = job,
        canSummarize = canSummarize,
        actions = rememberJobActions(
            job = job,
            viewModel = viewModel,
            clipboard = clipboard,
            scope = scope,
            context = context,
        ),
    )
}

@Composable
private fun ProviderSummaryCard(
    transcriptionProviderLabel: String,
    summarizerProviderLabel: String,
    onNavigateToSettings: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onNavigateToSettings),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.main_transcription_summary, transcriptionProviderLabel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(R.string.main_summarizer_summary, summarizerProviderLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.main_go_to_settings),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun StatusCard(status: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(R.string.main_status_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BatchProgressCard(state: BatchProgressState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.main_batch_progress, state.currentFileNumber, state.totalFiles),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            LinearProgressIndicator(
                progress = { state.completedFiles.toFloat() / state.totalFiles.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmptyJobsState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.main_empty_jobs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClearCompletedRow(onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClick) {
            Text(stringResource(R.string.main_clear_completed))
        }
    }
}

@Composable
private fun rememberJobActions(
    job: TranscriptionJobState,
    viewModel: MainViewModel,
    clipboard: Clipboard,
    scope: CoroutineScope,
    context: Context,
): TranscriptionJobCardActions {
    val actions = viewModel.mainScreenActions
    val shareTranscriptTitle = stringResource(R.string.share_transcription_title)
    val shareSummaryTitle = stringResource(R.string.share_summary_title)
    return remember(
        job.id,
        job.transcript,
        job.summary,
        actions,
        clipboard,
        scope,
        context,
        shareTranscriptTitle,
        shareSummaryTitle,
    ) {
        TranscriptionJobCardActions(
            summarize = { actions.summarizeJob(job.id) },
            clearSummary = { actions.clearSummary(job.id) },
            clearTranscript = { actions.clearTranscript(job.id) },
            copyTranscript = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", job.transcript)))
                    Toast.makeText(
                        context,
                        context.getString(R.string.main_copied_transcript),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            copySummary = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", job.summary)))
                    Toast.makeText(context, context.getString(R.string.main_copied_summary), Toast.LENGTH_SHORT).show()
                }
            },
            shareTranscript = { shareText(context, job.transcript, shareTranscriptTitle) },
            shareSummary = { shareText(context, job.summary, shareSummaryTitle) },
            retryTranscription = { actions.retryJob(job.id) },
            retrySummary = { actions.summarizeJob(job.id) },
            cancel = { actions.cancelJob(job.id) },
            cancelTranscription = { actions.cancelJob(job.id) },
            cancelSummary = { actions.cancelSummary(job.id) },
            delete = { actions.deleteJob(job.id) },
        )
    }
}

private fun shareText(context: Context, text: String, title: String? = null) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(sendIntent, title))
}
