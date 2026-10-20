package com.andreacovelli.whispertranscriber.ui.main

import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.background.PostTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.queue.JobOperationRegistry
import com.andreacovelli.whispertranscriber.queue.RetryResult
import com.andreacovelli.whispertranscriber.services.SummarizationService
import com.andreacovelli.whispertranscriber.services.TranscriptionQueueService
import com.andreacovelli.whispertranscriber.services.ValidationService
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.TranscriptionQueueSettings
import com.andreacovelli.whispertranscriber.settings.decodeHistoricalSummarizerProvider
import com.andreacovelli.whispertranscriber.settings.decodeTranscriptionProvider
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import com.andreacovelli.whispertranscriber.ui.TextTransfer
import com.andreacovelli.whispertranscriber.ui.screens.BatchProgressState
import com.andreacovelli.whispertranscriber.ui.screens.buildBatchProgressState
import com.andreacovelli.whispertranscriber.userMessageOr
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class CustomModelValidation {
    object Idle : CustomModelValidation()
    object Validating : CustomModelValidation()
    data class Valid(val displayName: String) : CustomModelValidation()
    data class Error(val message: String) : CustomModelValidation()
}

sealed class KeyValidationState {
    object Idle : KeyValidationState()
    object Validating : KeyValidationState()
    data class Error(val message: String) : KeyValidationState()
}

sealed class RegionValidationState {
    object Idle : RegionValidationState()
    object Validating : RegionValidationState()
    data class Valid(val region: String) : RegionValidationState()
    data class Error(val message: String) : RegionValidationState()
}

data class UiState(
    val status: String = "",
    val provider: TranscriptionProvider = TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
    val summarizerProvider: TranscriptionProvider = TranscriptionProvider.GEMINI_FLASH,
    val microsoftSpeechApiKeyState: ApiKeyState = ApiKeyState.Missing,
    val microsoftSpeechKeyValidation: KeyValidationState = KeyValidationState.Idle,
    val microsoftSpeechRegion: String = "",
    val microsoftSpeechRegionValidation: RegionValidationState = RegionValidationState.Idle,
    val geminiApiKeyState: ApiKeyState = ApiKeyState.Missing,
    val geminiKeyValidation: KeyValidationState = KeyValidationState.Idle,
    val transcriptionCustomGeminiModelName: String = "",
    val summarizerCustomGeminiModelName: String = "",
    val transcriptionCustomModelValidation: CustomModelValidation = CustomModelValidation.Idle,
    val summarizerCustomModelValidation: CustomModelValidation = CustomModelValidation.Idle,
    val automaticSummarizationEnabled: Boolean = false,
    val backgroundShareSummaryNotificationsEnabled: Boolean = false,
    val speakerDiarizationEnabled: Boolean = false,
    val jobs: List<TranscriptionJobState> = emptyList(),
    val batchProgressState: BatchProgressState? = null,
    val jobsLoaded: Boolean = false,
    val latestRevealRequestToken: Long = 0L,
    val mainNavigationRequest: MainNavigationRequest? = null,
    val jobRevealRequest: JobRevealRequest? = null,
)

data class TranscriptionJobState(
    val id: Int = 0,
    val timestamp: Long = 0L,
    val uri: Uri? = null,
    val displayName: String = "",
    val provider: TranscriptionProvider = TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
    val requestedModel: String = "",
    // Stored status: a JOB_STATUS_* code or provider progress text. The job card resolves the label.
    val status: String = JOB_STATUS_QUEUED,
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
    val error: String? = null,
    val summaryError: String? = null,
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
    error = error,
    summaryError = summaryError,
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

private val TranscriptionJob.hasSummaryBlockingState: Boolean
    get() = isRunning || isSummarizing || error != null || summaryError != null

private val UiState.isAutoSummaryActive: Boolean
    get() = automaticSummarizationEnabled && geminiApiKeyState is ApiKeyState.Present

private val TranscriptionJob.isReadyForAutoSummary: Boolean
    get() = !hasSummaryBlockingState && transcript.isNotBlank() && summary.isBlank() && !autoSummaryAttempted

internal fun UiState.toTranscriptionQueueSettings(): TranscriptionQueueSettings = TranscriptionQueueSettings(
    provider = provider,
    customModelName = transcriptionCustomGeminiModelName,
    speakerDiarizationEnabled = speakerDiarizationEnabled,
)

internal fun UiState.toSummarizationSettings(): SummarizationSettings = SummarizationSettings(
    provider = summarizerProvider,
    customModelName = summarizerCustomGeminiModelName,
)

internal fun UiState.toAutomaticSummarizationWorkSpec(): PostTranscriptionWorkSpec? {
    if (!automaticSummarizationEnabled || geminiApiKeyState !is ApiKeyState.Present) return null
    return PostTranscriptionWorkSpec(
        summarizeAfterTranscription = true,
        notifyResult = false,
        summarizationSettings = toSummarizationSettings(),
    )
}

internal fun resolveShareQueueSettings(
    uiState: UiState,
    transcriptionSettingsHydrated: Boolean,
    persistedSettings: TranscriptionQueueSettings,
): TranscriptionQueueSettings = if (transcriptionSettingsHydrated) {
    uiState.toTranscriptionQueueSettings()
} else {
    persistedSettings
}

@HiltViewModel
class MainViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val settings: ApiKeyManager,
    private val repository: TranscriptionRepository,
    private val queueService: TranscriptionQueueService,
    private val operations: JobOperationRegistry,
    private val textTransfer: TextTransfer,
    private val validationService: ValidationService,
    private val summarizationService: SummarizationService,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    suspend fun shareText(text: String, title: String, launch: (Intent) -> Unit): Boolean =
        textTransfer.share(text, title, launch)

    private val _uiState = MutableStateFlow(
        UiState(status = appContext.getString(R.string.main_waiting_for_audios)),
    )
    val uiState = _uiState.asStateFlow()
    private val _events = Channel<Int>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val summaryJobs = ConcurrentHashMap<Int, Job>()

    // Automatic summaries run one at a time. Both are only touched on the main dispatcher.
    private val autoSummaryRequests = Channel<Int>(Channel.UNLIMITED)
    private val pendingAutoSummaries = mutableSetOf<Int>()
    private val latestJobs = MutableStateFlow<List<TranscriptionJob>>(emptyList())
    private val transcriptionSettingsHydrated = MutableStateFlow(false)
    private val settingsDelegate = MainSettingsDelegate(
        getState = { _uiState.value },
        dispatch = ::dispatch,
        settings = settings,
        validationService = validationService,
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        markFinishedJobsAutoSummaryAttempted = repository::markFinishedJobsAutoSummaryAttempted,
        resolveMessage = { message -> message.resolve(appContext) },
        notify = { _events.trySend(it) },
    )

    init {
        viewModelScope.launch {
            launch {
                settings.transcriptionQueueSettingsFlow.collect { queueSettings ->
                    reduce(MainMsg.TranscriptionSettingsLoaded(queueSettings))
                    transcriptionSettingsHydrated.value = true
                }
            }
            launch {
                settings.summarizerProviderFlow.collect { p ->
                    reduce(MainMsg.SummarizerProviderLoaded(p))
                }
            }
            launch {
                settings.microsoftSpeechApiKeyStateFlow.collect { state ->
                    reduce(MainMsg.MicrosoftSpeechApiKeyStateChanged(state))
                }
            }
            launch {
                settings.geminiApiKeyStateFlow.collect { state ->
                    reduce(MainMsg.GeminiApiKeyStateChanged(state))
                }
            }
            launch {
                settings.microsoftSpeechRegionFlow.collect { region ->
                    reduce(MainMsg.MicrosoftSpeechRegionLoaded(region))
                }
            }
            launch {
                settings.summarizerCustomGeminiModelNameFlow.collect { name ->
                    reduce(MainMsg.SummarizerCustomModelLoaded(name.orEmpty()))
                }
            }
            launch {
                settings.automaticSummarizationFlow.collect { enabled ->
                    reduce(MainMsg.AutomaticSummarizationLoaded(enabled))
                }
            }
            launch {
                settings.backgroundShareSummaryNotificationsEnabledFlow.collect { enabled ->
                    reduce(MainMsg.BackgroundShareSummaryNotificationsLoaded(enabled))
                }
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
                    latestJobs.value = jobsList
                }
            }
            launch {
                // Rescan when auto-summary becomes active as well, so a completion observed while the
                // setting or key was still propagating is not missed. Already-marked jobs fail the claim.
                combine(
                    latestJobs,
                    _uiState.map { state -> state.isAutoSummaryActive }.distinctUntilChanged(),
                ) { jobsList, isActive -> if (isActive) jobsList else emptyList() }
                    .collect { jobsList ->
                        jobsList.forEach { job ->
                            if (job.isReadyForAutoSummary && pendingAutoSummaries.add(job.id)) {
                                autoSummaryRequests.trySend(job.id)
                            }
                        }
                    }
            }
            launch {
                for (jobId in autoSummaryRequests) {
                    try {
                        if (_uiState.value.isAutoSummaryActive) {
                            runSummarization(jobId, allowResummarize = false).join()
                        }
                    } finally {
                        pendingAutoSummaries.remove(jobId)
                    }
                }
            }
        }

        viewModelScope.launch {
            queueService.resumePendingQueue()
        }
    }

    fun dispatch(msg: MainMsg) {
        val reducedState = reduce(msg)
        if (handleQueueMsg(msg, reducedState)) return
        handleSettingsMsg(msg)
    }

    private fun handleQueueMsg(msg: MainMsg, reducedState: UiState): Boolean {
        when (msg) {
            is MainMsg.SharedAudioReceived -> {
                val requestToken = (reducedState.jobRevealRequest as? JobRevealRequest.AwaitingCreation)?.token
                if (requestToken != null) {
                    enqueueSharedAudio(msg.uris, requestToken)
                }
            }
            MainMsg.ClearCompletedJobs -> viewModelScope.launch { queueService.clearCompletedJobs() }
            is MainMsg.SummarizeJob -> runSummarization(msg.jobId, allowResummarize = msg.allowResummarize)
            is MainMsg.ClearSummary -> viewModelScope.launch {
                queueService.clearJobContent(
                    msg.jobId,
                    com.andreacovelli.whispertranscriber.data.HistoryContentField.SUMMARY,
                )
            }
            is MainMsg.ClearTranscript -> viewModelScope.launch { queueService.clearJobTranscription(msg.jobId) }
            is MainMsg.RetryJob -> retryJob(msg.jobId)
            is MainMsg.CancelJob -> viewModelScope.launch { queueService.cancelJob(msg.jobId) }
            is MainMsg.CancelSummary -> cancelSummary(msg.jobId)
            is MainMsg.DeleteJob -> viewModelScope.launch { queueService.deleteJob(msg.jobId) }
            else -> return false
        }
        return true
    }

    private fun handleSettingsMsg(msg: MainMsg): Boolean {
        when (msg) {
            is MainMsg.SetProvider -> settingsDelegate.actions.setProvider(msg.provider)
            is MainMsg.SetSummarizerProvider -> settingsDelegate.actions.setSummarizerProvider(msg.provider)
            is MainMsg.SetAutomaticSummarization -> settingsDelegate.actions.setAutomaticSummarization(msg.enabled)
            is MainMsg.SetBackgroundShareSummaryNotificationsEnabled ->
                settingsDelegate.actions.setBackgroundShareSummaryNotificationsEnabled(msg.enabled)
            is MainMsg.SetSpeakerDiarization -> settingsDelegate.actions.setSpeakerDiarization(msg.enabled)
            is MainMsg.SetMicrosoftSpeechRegion -> settingsDelegate.actions.setMicrosoftSpeechRegion(msg.region)
            is MainMsg.SaveMicrosoftSpeechKey -> settingsDelegate.actions.saveMicrosoftSpeechKey(msg.key)
            is MainMsg.SaveGeminiKey -> settingsDelegate.actions.saveGeminiKey(msg.key)
            is MainMsg.ValidateTranscriptionModel -> settingsDelegate.actions.validateTranscriptionModel(msg.modelName)
            is MainMsg.ValidateSummarizerModel -> settingsDelegate.actions.validateSummarizerModel(msg.modelName)
            MainMsg.ResetMicrosoftSpeechRegionValidation ->
                settingsDelegate.actions.resetMicrosoftSpeechRegionValidation()
            MainMsg.ResetTranscriptionModelValidation -> settingsDelegate.actions.resetTranscriptionModelValidation()
            MainMsg.ResetSummarizerModelValidation -> settingsDelegate.actions.resetSummarizerModelValidation()
            MainMsg.ResetMicrosoftSpeechKeyValidation -> settingsDelegate.actions.resetMicrosoftSpeechKeyValidation()
            MainMsg.ResetGeminiKeyValidation -> settingsDelegate.actions.resetGeminiKeyValidation()
            MainMsg.ForgetMicrosoftSpeechKey -> settingsDelegate.actions.forgetMicrosoftSpeechKey()
            MainMsg.ForgetGeminiKey -> settingsDelegate.actions.forgetGeminiKey()
            else -> return false
        }
        return true
    }

    suspend fun loadMicrosoftSpeechKeyForDisplay(): String? = settingsDelegate.actions.loadMicrosoftSpeechKey()

    suspend fun loadGeminiKeyForDisplay(): String? = settingsDelegate.actions.loadGeminiKey()

    private fun enqueueSharedAudio(uris: List<Uri>, requestToken: Long) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            val queuingMessage =
                appContext.resources.getQuantityString(R.plurals.main_queuing_files, uris.size, uris.size)
            reduce(MainMsg.StatusChanged(queuingMessage))
            val firstCreatedJobId = try {
                val queueSettings = resolveTranscriptionQueueSettingsForShare()
                val result = queueService.enqueueSharedAudio(
                    uris = uris,
                    provider = queueSettings.provider,
                    customModelName = queueSettings.customModelName,
                    speakerDiarizationEnabled = queueSettings.speakerDiarizationEnabled,
                    postTranscriptionWorkSpec = resolveAutomaticSummarizationWorkSpecForQueue(),
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
        val message = error.userMessageOr(R.string.background_share_queue_failed_body).resolve(appContext)
        reduce(MainMsg.StatusChanged(message))
        return null
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

    private fun runSummarization(jobId: Int, allowResummarize: Boolean): Job {
        val previousSummaryJob = summaryJobs.remove(jobId)
        return viewModelScope.launch {
            operations.track(jobId) {
                previousSummaryJob?.cancelAndJoin()
                val currentState = _uiState.value
                val result = summarizationService.summarize(
                    jobId = jobId,
                    settings = currentState.toSummarizationSettings(),
                    allowResummarize = allowResummarize,
                    fallbackStatusMessage = currentState.status,
                )
                reduce(MainMsg.StatusChanged(result.statusMessage))
            }
        }.also { job ->
            summaryJobs[jobId] = job
            job.invokeOnCompletion {
                summaryJobs.remove(jobId, job)
            }
        }
    }

    private fun cancelSummary(jobId: Int) {
        val localSummaryJob = summaryJobs.remove(jobId)
        viewModelScope.launch {
            val backgroundCancelled = queueService.cancelBackgroundSummaryIfRunning(jobId)
            val statusMessage = if (backgroundCancelled) {
                appContext.getString(R.string.summarization_status_cancelled)
            } else {
                summarizationService.cancelSummary(jobId)
            }
            localSummaryJob?.cancel()
            reduce(MainMsg.StatusChanged(statusMessage))
        }
    }

    private fun reduce(msg: MainMsg): UiState = _uiState.updateAndGet { state -> MainReducer.reduce(state, msg) }

    override fun onCleared() {
        summaryJobs.values.forEach(Job::cancel)
        summaryJobs.clear()
    }

    private suspend fun resolveTranscriptionQueueSettingsForShare(): TranscriptionQueueSettings {
        val currentState = _uiState.value
        val hydrated = transcriptionSettingsHydrated.value
        return resolveShareQueueSettings(
            uiState = currentState,
            transcriptionSettingsHydrated = hydrated,
            persistedSettings = if (hydrated) {
                currentState.toTranscriptionQueueSettings()
            } else {
                settings.getTranscriptionQueueSettings()
            },
        )
    }

    private suspend fun resolveAutomaticSummarizationWorkSpecForQueue(): PostTranscriptionWorkSpec? {
        _uiState.value.toAutomaticSummarizationWorkSpec()?.let { return it }

        if (!settings.automaticSummarizationFlow.first()) return null
        val keyChars = settings.getGeminiApiKey() ?: return null
        val hasGeminiKey = try {
            keyChars.isNotEmpty()
        } finally {
            keyChars.fill('\u0000')
        }
        if (!hasGeminiKey) return null

        return PostTranscriptionWorkSpec(
            summarizeAfterTranscription = true,
            notifyResult = false,
            summarizationSettings = settings.getSummarizationSettings(),
        )
    }
}
