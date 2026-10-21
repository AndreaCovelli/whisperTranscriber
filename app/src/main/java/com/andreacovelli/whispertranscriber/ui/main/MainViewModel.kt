package com.andreacovelli.whispertranscriber.ui.main

import android.content.Intent
import android.database.SQLException
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andreacovelli.whispertranscriber.QuantityText
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.StoredMessages
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.background.SummaryScheduler
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.queue.RetryResult
import com.andreacovelli.whispertranscriber.services.TranscriptionQueueService
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.decodeHistoricalSummarizerProvider
import com.andreacovelli.whispertranscriber.settings.decodeTranscriptionProvider
import com.andreacovelli.whispertranscriber.summary.SummaryStyle
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RUNNING
import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import com.andreacovelli.whispertranscriber.ui.TextTransfer
import com.andreacovelli.whispertranscriber.ui.components.SummaryStyleChoices
import com.andreacovelli.whispertranscriber.ui.screens.BatchProgressState
import com.andreacovelli.whispertranscriber.ui.screens.buildBatchProgressState
import com.andreacovelli.whispertranscriber.userMessageOr
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TranscriptionJobState(
    val id: Int = 0,
    val timestamp: Long = 0L,
    val uri: Uri? = null,
    val displayName: String = "",
    val provider: TranscriptionProvider = TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
    val requestedModel: String = "",
    val status: String = JOB_STATUS_QUEUED,
    // Progress text while running; null otherwise.
    val statusMessage: UserMessage? = null,
    val progress: Float = 0f, // 0..1 conversion progress
    val transcript: String = "",
    val language: String = "",
    val model: String = "",
    val summary: String = "",
    val summaryProvider: TranscriptionProvider? = null,
    val summaryModel: String = "",
    val summaryStatus: String = "",
    val autoSummaryAttempted: Boolean = false,
    val batchId: String = "",
    val historyId: Int? = null,
    val error: UserMessage? = null,
    val summaryError: UserMessage? = null,
    val isRunning: Boolean = false,
    val isSummarizing: Boolean = false,
    val canRetry: Boolean = false,
)

fun TranscriptionJob.toUiState(): TranscriptionJobState = TranscriptionJobState(
    id = id,
    timestamp = timestamp,
    uri = uri.toUri(),
    displayName = displayName,
    provider = decodeTranscriptionProvider(providerName),
    requestedModel = requestedModel.orEmpty(),
    status = status,
    statusMessage = if (status == JOB_STATUS_RUNNING) StoredMessages.decode(statusMessage) else null,
    progress = progress,
    transcript = transcript,
    language = language,
    model = model,
    summary = summary,
    summaryProvider = summaryProviderName
        .takeIf(String::isNotBlank)
        ?.let(::decodeHistoricalSummarizerProvider),
    summaryModel = summaryModel,
    summaryStatus = summaryStatus,
    autoSummaryAttempted = autoSummaryAttempted,
    batchId = batchId.orEmpty(),
    historyId = historyId,
    error = StoredMessages.decode(error),
    summaryError = StoredMessages.decode(summaryError),
    isRunning = isRunning,
    isSummarizing = isSummarizing,
    canRetry = status in setOf(JOB_STATUS_ERROR, JOB_STATUS_CANCELLED) &&
        !isRunning && !isSummarizing && !stagedFilePath.isNullOrBlank(),
)

internal data class JobPresentationState(
    val jobs: List<TranscriptionJobState>,
    val batchProgressState: BatchProgressState?,
)

internal fun buildJobPresentationState(jobs: List<TranscriptionJob>): JobPresentationState {
    val jobStates = jobs.map(TranscriptionJob::toUiState)
    return JobPresentationState(
        jobs = jobStates,
        batchProgressState = buildBatchProgressState(jobStates),
    )
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: ApiKeyManager,
    private val repository: TranscriptionRepository,
    private val queueService: TranscriptionQueueService,
    private val textTransfer: TextTransfer,
    private val summaryScheduler: SummaryScheduler,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    suspend fun shareText(text: String, title: String, launch: (Intent) -> Unit): Boolean =
        textTransfer.share(text, title, launch)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()
    private val _events = Channel<Int>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            launch {
                combine(
                    settings.transcriptionQueueSettingsFlow,
                    settings.summarizationSettingsFlow,
                    settings.geminiApiKeyStateFlow,
                ) { transcription, summarization, geminiKeyState ->
                    ProviderSummary(
                        transcriptionProvider = transcription.provider,
                        transcriptionCustomModelName = transcription.customModelName,
                        summarizerProvider = summarization.provider,
                        summarizerCustomModelName = summarization.customModelName,
                        canSummarize = geminiKeyState is ApiKeyState.Present,
                        summaryStyleChoices = SummaryStyleChoices(
                            defaultStyle = summarization.style,
                            customPromptAvailable = summarization.customPrompt.isNotBlank(),
                        ),
                    )
                }.collect { summary -> reduce(MainMsg.ProviderSummaryChanged(summary)) }
            }
            launch {
                repository.jobsState.collect { jobsList ->
                    val presentationState = withContext(ioDispatcher) {
                        buildJobPresentationState(jobsList)
                    }
                    reduce(
                        MainMsg.JobPresentationChanged(
                            jobs = presentationState.jobs,
                            batchProgressState = presentationState.batchProgressState,
                        ),
                    )
                }
            }
        }

        viewModelScope.launch {
            queueService.resumePendingQueue()
        }
    }

    fun dispatch(msg: MainMsg) {
        val reducedState = reduce(msg)
        when (msg) {
            is MainMsg.SharedAudioReceived -> {
                val requestToken = (reducedState.jobRevealRequest as? JobRevealRequest.AwaitingCreation)?.token
                if (requestToken != null) {
                    enqueueSharedAudio(msg.uris, requestToken)
                }
            }
            MainMsg.ClearCompletedJobs -> viewModelScope.launch { queueService.clearCompletedJobs() }
            is MainMsg.SummarizeJob -> requestSummary(msg.jobId, msg.style)
            is MainMsg.ClearSummary -> viewModelScope.launch {
                queueService.clearJobContent(
                    msg.jobId,
                    HistoryContentField.SUMMARY,
                )
            }
            is MainMsg.ClearTranscript -> viewModelScope.launch { queueService.clearJobTranscription(msg.jobId) }
            is MainMsg.SaveJobContent -> saveJobContent(msg.jobId, msg.field, msg.destination)
            is MainMsg.RetryJob -> retryJob(msg.jobId)
            is MainMsg.CancelJob -> viewModelScope.launch { queueService.cancelJob(msg.jobId) }
            is MainMsg.CancelSummary -> cancelSummary(msg.jobId)
            is MainMsg.DeleteJob -> viewModelScope.launch { queueService.deleteJob(msg.jobId) }
            else -> Unit
        }
    }

    private fun enqueueSharedAudio(uris: List<Uri>, requestToken: Long) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            reduce(MainMsg.StatusChanged(UserMessage.verbatim(QuantityText(R.plurals.main_queuing_files, uris.size))))
            val firstCreatedJobId = try {
                val queueSettings = settings.getTranscriptionQueueSettings()
                val result = queueService.enqueueSharedAudio(
                    uris = uris,
                    provider = queueSettings.provider,
                    customModelName = queueSettings.customModelName,
                    speakerDiarizationEnabled = queueSettings.speakerDiarizationEnabled,
                    hints = queueSettings.hints(),
                    postTranscriptionWorkSpec = queueService.resolveAutomaticSummarizationWorkSpec(),
                )
                reduce(MainMsg.StatusChanged(result.statusMessage))
                result.firstCreatedJobId
            } catch (e: IOException) {
                reportShareQueueFailure(e)
            } catch (e: SecurityException) {
                reportShareQueueFailure(e)
            } catch (e: SQLException) {
                reportShareQueueFailure(e)
            } catch (e: IllegalArgumentException) {
                reportShareQueueFailure(e)
            } catch (e: IllegalStateException) {
                reportShareQueueFailure(e)
            }
            reduce(MainMsg.SharedAudioQueueFinished(requestToken, firstCreatedJobId))
        }
    }

    private fun reportShareQueueFailure(error: Exception): Int? {
        reduce(MainMsg.StatusChanged(error.userMessageOr(R.string.background_share_queue_failed_body)))
        return null
    }

    private fun saveJobContent(jobId: Int, field: HistoryContentField, destination: Uri) {
        viewModelScope.launch {
            val text = repository.getJobContent(jobId, field)
            val saved = text.isNotBlank() && textTransfer.save(text, destination)
            _events.send(if (saved) R.string.text_save_success else R.string.text_save_failed)
        }
    }

    private fun retryJob(jobId: Int) {
        viewModelScope.launch {
            val result = queueService.retryJob(jobId)
            val messageId = when (result) {
                RetryResult.ACCEPTED -> null
                RetryResult.QUEUE_FULL -> R.string.retry_queue_full
                RetryResult.UNAVAILABLE_AUDIO -> R.string.queued_audio_unavailable
                RetryResult.INELIGIBLE_STATUS -> R.string.retry_ineligible
            }
            if (messageId != null) {
                _events.send(messageId)
            }
        }
    }

    private fun requestSummary(jobId: Int, style: SummaryStyle?) {
        val displayName = _uiState.value.jobs.firstOrNull { it.id == jobId }?.displayName.orEmpty()
        viewModelScope.launch {
            val summarizationSettings = settings.getSummarizationSettings()
                .let { current -> style?.let { current.copy(style = it) } ?: current }
            val workId = summaryScheduler.enqueue(jobId, displayName, summarizationSettings)
            summaryScheduler.statusMessages(workId).collect { statusMessage ->
                statusMessage?.let { reduce(MainMsg.StatusChanged(it)) }
            }
        }
    }

    private fun cancelSummary(jobId: Int) {
        viewModelScope.launch {
            val statusMessage = if (queueService.cancelBackgroundSummaryIfRunning(jobId)) {
                UserMessage(R.string.summarization_status_cancelled)
            } else {
                summaryScheduler.cancel(jobId)
            }
            reduce(MainMsg.StatusChanged(statusMessage))
        }
    }

    private fun reduce(msg: MainMsg): MainUiState = _uiState.updateAndGet { state -> MainReducer.reduce(state, msg) }
}
