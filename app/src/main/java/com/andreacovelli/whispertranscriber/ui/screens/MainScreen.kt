package com.andreacovelli.whispertranscriber.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.history.suggestedTextExportFileName
import com.andreacovelli.whispertranscriber.recording.requiredRecordingPermissions
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RETRY_SCHEDULED
import com.andreacovelli.whispertranscriber.ui.asString
import com.andreacovelli.whispertranscriber.ui.components.MainActionButtons
import com.andreacovelli.whispertranscriber.ui.components.SummaryStyleChoices
import com.andreacovelli.whispertranscriber.ui.components.TextSaveTarget
import com.andreacovelli.whispertranscriber.ui.components.TranscriptionJobCard
import com.andreacovelli.whispertranscriber.ui.components.TranscriptionJobCardActions
import com.andreacovelli.whispertranscriber.ui.components.providerDisplayLabel
import com.andreacovelli.whispertranscriber.ui.components.rememberTextFileSaveLauncher
import com.andreacovelli.whispertranscriber.ui.copyTextWithFeedback
import com.andreacovelli.whispertranscriber.ui.main.JobRevealRequest
import com.andreacovelli.whispertranscriber.ui.main.MainMsg
import com.andreacovelli.whispertranscriber.ui.main.MainUiState
import com.andreacovelli.whispertranscriber.ui.main.MainViewModel
import com.andreacovelli.whispertranscriber.ui.main.TranscriptionJobState
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
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.dispatch(MainMsg.SharedAudioReceived(uris))
    }
    val saveTextFile = rememberTextFileSaveLauncher { target, destination ->
        viewModel.dispatch(MainMsg.SaveJobContent(target.id, target.field, destination))
    }
    val canRecord = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }
    val recordingPermissions = remember { requiredRecordingPermissions() }
    val recordingPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val deniedMessage = when {
                results[Manifest.permission.RECORD_AUDIO] == false -> R.string.recording_microphone_permission_denied
                results.containsValue(false) -> R.string.recording_storage_permission_denied
                else -> null
            }
            if (deniedMessage == null) {
                viewModel.dispatch(MainMsg.StartRecording)
            } else {
                Toast.makeText(context, deniedMessage, Toast.LENGTH_LONG).show()
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.main_title)) },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(
                            painterResource(R.drawable.ic_history),
                            contentDescription = stringResource(R.string.main_open_history),
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.main_open_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            MainActionButtons(
                recording = uiState.recording,
                canRecord = canRecord,
                onRecord = {
                    val granted = recordingPermissions.all { permission ->
                        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                    }
                    if (granted) {
                        viewModel.dispatch(MainMsg.StartRecording)
                    } else {
                        recordingPermissionLauncher.launch(recordingPermissions)
                    }
                },
                onStopRecording = { viewModel.dispatch(MainMsg.StopRecording) },
                onDiscardRecording = { viewModel.dispatch(MainMsg.DiscardRecording) },
                onPickAudio = { audioPicker.launch(arrayOf(AUDIO_MIME_TYPE_FILTER)) },
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
            onSaveText = saveTextFile,
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
    uiState: MainUiState,
    onNavigateToSettings: () -> Unit,
    onClearCompleted: () -> Unit,
    clipboard: Clipboard,
    scope: CoroutineScope,
    context: Context,
    listState: LazyListState,
    onSaveText: (TextSaveTarget, String) -> Unit,
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
            canSummarize = uiState.providerSummary.canSummarize,
            summaryStyleChoices = uiState.providerSummary.summaryStyleChoices,
            clipboard = clipboard,
            scope = scope,
            context = context,
            onSaveText = onSaveText,
        )
    }
}

@Composable
internal fun MainJobList(
    uiState: MainUiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit,
    onClearCompleted: () -> Unit,
    onJobRevealHandled: (Long) -> Unit,
    jobContent: @Composable (TranscriptionJobState) -> Unit,
) {
    val providers = uiState.providerSummary
    val transcriptionProviderLabel =
        providerDisplayLabel(providers.transcriptionProvider, providers.transcriptionCustomModelName)
    val summarizerProviderLabel =
        providerDisplayLabel(providers.summarizerProvider, providers.summarizerCustomModelName)
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
        // Extra bottom space keeps the last card's actions clear of the stacked floating action buttons.
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 168.dp),
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

internal fun resolveMainJobItemIndex(uiState: MainUiState, jobId: Int): Int? {
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

// Matches the share-intent filters in the manifest.
private const val AUDIO_MIME_TYPE_FILTER = "audio/*"

@Composable
private fun MainJobCard(
    viewModel: MainViewModel,
    job: TranscriptionJobState,
    canSummarize: Boolean,
    summaryStyleChoices: SummaryStyleChoices,
    clipboard: Clipboard,
    scope: CoroutineScope,
    context: Context,
    onSaveText: (TextSaveTarget, String) -> Unit,
) {
    TranscriptionJobCard(
        job = job,
        canSummarize = canSummarize,
        summaryStyleChoices = summaryStyleChoices,
        actions = rememberJobActions(
            job = job,
            viewModel = viewModel,
            clipboard = clipboard,
            scope = scope,
            context = context,
            onSaveText = onSaveText,
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
                painterResource(R.drawable.ic_arrow_forward),
                contentDescription = stringResource(R.string.main_go_to_settings),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun StatusCard(status: UserMessage) {
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
            Text(status.asString(), style = MaterialTheme.typography.bodyMedium)
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
                painter = painterResource(R.drawable.ic_mic),
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
    onSaveText: (TextSaveTarget, String) -> Unit,
): TranscriptionJobCardActions {
    val shareTranscriptTitle = stringResource(R.string.share_transcription_title)
    val shareSummaryTitle = stringResource(R.string.share_summary_title)
    return remember(
        job.id,
        job.displayName,
        job.transcript,
        job.summary,
        viewModel,
        clipboard,
        scope,
        context,
        shareTranscriptTitle,
        shareSummaryTitle,
        onSaveText,
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
            saveTranscript = {
                onSaveText(
                    TextSaveTarget(job.id, HistoryContentField.TRANSCRIPT),
                    suggestedTextExportFileName(job.displayName, HistoryContentField.TRANSCRIPT),
                )
            },
            saveSummary = {
                onSaveText(
                    TextSaveTarget(job.id, HistoryContentField.SUMMARY),
                    suggestedTextExportFileName(job.displayName, HistoryContentField.SUMMARY),
                )
            },
            retryTranscription = { viewModel.dispatch(MainMsg.RetryJob(job.id)) },
            retrySummary = { viewModel.dispatch(MainMsg.SummarizeJob(job.id)) },
            cancel = { viewModel.dispatch(MainMsg.CancelJob(job.id)) },
            cancelTranscription = { viewModel.dispatch(MainMsg.CancelJob(job.id)) },
            cancelSummary = { viewModel.dispatch(MainMsg.CancelSummary(job.id)) },
            delete = { viewModel.dispatch(MainMsg.DeleteJob(job.id)) },
            summarizeWithStyle = { style -> viewModel.dispatch(MainMsg.SummarizeJob(job.id, style)) },
        )
    }
}

private suspend fun shareText(viewModel: MainViewModel, context: Context, text: String, title: String) {
    if (!viewModel.shareText(text, title, context::startActivity)) {
        Toast.makeText(context, R.string.text_share_failed, Toast.LENGTH_SHORT).show()
    }
}
