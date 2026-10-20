package com.andreacovelli.whispertranscriber.ui.screens

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RETRY_SCHEDULED
import com.andreacovelli.whispertranscriber.ui.components.TranscriptionJobCard
import com.andreacovelli.whispertranscriber.ui.components.TranscriptionJobCardActions
import com.andreacovelli.whispertranscriber.ui.components.providerDisplayLabel
import com.andreacovelli.whispertranscriber.ui.copyTextWithFeedback
import com.andreacovelli.whispertranscriber.ui.main.JobRevealRequest
import com.andreacovelli.whispertranscriber.ui.main.MainMsg
import com.andreacovelli.whispertranscriber.ui.main.MainViewModel
import com.andreacovelli.whispertranscriber.ui.main.TranscriptionJobState
import com.andreacovelli.whispertranscriber.ui.main.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onNavigateToHistory: () -> Unit, onNavigateToSettings: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

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
            onClearCompleted = { viewModel.dispatch(MainMsg.ClearCompletedJobs) },
            clipboard = clipboard,
            scope = scope,
            context = context,
            listState = listState,
            onJobRevealHandled = { requestToken ->
                viewModel.dispatch(MainMsg.JobRevealHandled(requestToken))
            },
        )
    }
}

@Composable
private fun MainScreenContent(
    viewModel: MainViewModel,
    paddingValues: PaddingValues,
    uiState: UiState,
    onNavigateToSettings: () -> Unit,
    onClearCompleted: () -> Unit,
    clipboard: Clipboard,
    scope: CoroutineScope,
    context: Context,
    listState: LazyListState,
    onJobRevealHandled: (Long) -> Unit,
) {
    MainJobList(
        uiState = uiState,
        listState = listState,
        modifier = Modifier.padding(paddingValues),
        onNavigateToSettings = onNavigateToSettings,
        onClearCompleted = onClearCompleted,
        onJobRevealHandled = onJobRevealHandled,
    ) { job ->
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

@Composable
internal fun MainJobList(
    uiState: UiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit,
    onClearCompleted: () -> Unit,
    onJobRevealHandled: (Long) -> Unit,
    jobContent: @Composable (TranscriptionJobState) -> Unit,
) {
    val transcriptionProviderLabel = providerDisplayLabel(uiState.provider, uiState.transcriptionCustomGeminiModelName)
    val summarizerProviderLabel =
        providerDisplayLabel(uiState.summarizerProvider, uiState.summarizerCustomGeminiModelName)
    val lifecycleOwner = LocalLifecycleOwner.current
    val revealRequest = uiState.jobRevealRequest as? JobRevealRequest.Ready
    val revealItemIndex = revealRequest?.let { resolveMainJobItemIndex(uiState, it.jobId) }

    LaunchedEffect(revealRequest?.token, revealItemIndex, listState, lifecycleOwner) {
        if (revealRequest == null || revealItemIndex == null) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            listState.animateScrollToItem(revealItemIndex, scrollOffset = 0)
            onJobRevealHandled(revealRequest.token)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
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
            if (uiState.jobs.any { !it.isRunning && !it.isQueuedLike() }) {
                item { ClearCompletedRow(onClick = onClearCompleted) }
            }
            items(uiState.jobs, key = { it.id }) { job ->
                jobContent(job)
            }
        }
    }
}

internal fun resolveMainJobItemIndex(uiState: UiState, jobId: Int): Int? {
    val jobIndex = uiState.jobs.indexOfFirst { it.id == jobId }
    if (jobIndex == -1) return null

    var itemIndex = MAIN_LIST_FIXED_HEADER_COUNT
    if (uiState.batchProgressState != null) {
        itemIndex += 1
    }
    if (uiState.jobs.any { !it.isRunning && !it.isQueuedLike() }) {
        itemIndex += 1
    }
    return itemIndex + jobIndex
}

private fun TranscriptionJobState.isQueuedLike(): Boolean =
    status == JOB_STATUS_QUEUED || status == JOB_STATUS_RETRY_SCHEDULED

private const val MAIN_LIST_FIXED_HEADER_COUNT = 2

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
                text = pluralStringResource(
                    R.plurals.main_batch_progress,
                    state.totalFiles,
                    state.currentFileNumber,
                    state.totalFiles,
                ),
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
    val shareTranscriptTitle = stringResource(R.string.share_transcription_title)
    val shareSummaryTitle = stringResource(R.string.share_summary_title)
    return remember(
        job.id,
        job.transcript,
        job.summary,
        viewModel,
        clipboard,
        scope,
        context,
        shareTranscriptTitle,
        shareSummaryTitle,
    ) {
        TranscriptionJobCardActions(
            summarize = { viewModel.dispatch(MainMsg.SummarizeJob(job.id)) },
            clearSummary = { viewModel.dispatch(MainMsg.ClearSummary(job.id)) },
            clearTranscript = { viewModel.dispatch(MainMsg.ClearTranscript(job.id)) },
            copyTranscript = {
                scope.launch {
                    copyTextWithFeedback(context, clipboard, job.transcript, R.string.main_copied_transcript)
                }
            },
            copySummary = {
                scope.launch {
                    copyTextWithFeedback(context, clipboard, job.summary, R.string.main_copied_summary)
                }
            },
            shareTranscript = { scope.launch { shareText(viewModel, context, job.transcript, shareTranscriptTitle) } },
            shareSummary = { scope.launch { shareText(viewModel, context, job.summary, shareSummaryTitle) } },
            retryTranscription = { viewModel.dispatch(MainMsg.RetryJob(job.id)) },
            retrySummary = { viewModel.dispatch(MainMsg.SummarizeJob(job.id)) },
            cancel = { viewModel.dispatch(MainMsg.CancelJob(job.id)) },
            cancelTranscription = { viewModel.dispatch(MainMsg.CancelJob(job.id)) },
            cancelSummary = { viewModel.dispatch(MainMsg.CancelSummary(job.id)) },
            delete = { viewModel.dispatch(MainMsg.DeleteJob(job.id)) },
        )
    }
}

private suspend fun shareText(viewModel: MainViewModel, context: Context, text: String, title: String) {
    if (!viewModel.shareText(text, title, context::startActivity)) {
        Toast.makeText(context, R.string.text_share_failed, Toast.LENGTH_SHORT).show()
    }
}
