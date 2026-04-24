package com.andreacovelli.whispertranscriber.ui.main

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.background.PostTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.services.SummarizationService
import com.andreacovelli.whispertranscriber.services.TranscriptionQueueService
import com.andreacovelli.whispertranscriber.services.ValidationService
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.TranscriptionQueueSettings
import com.andreacovelli.whispertranscriber.settings.decodeHistoricalSummarizerProvider
import com.andreacovelli.whispertranscriber.settings.decodeHistoricalTranscriptionProvider
import com.andreacovelli.whispertranscriber.summary.TranscriptionSummarizationRepository
import com.andreacovelli.whispertranscriber.transcription.DISPLAY_JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import com.andreacovelli.whispertranscriber.transcription.displayJobStatus
import com.andreacovelli.whispertranscriber.transcription.displaySummaryStatus
import com.andreacovelli.whispertranscriber.ui.screens.BatchProgressState
import com.andreacovelli.whispertranscriber.ui.screens.buildBatchProgressState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
)

data class TranscriptionJobState(
    val id: Int = 0,
    val timestamp: Long = 0L,
    val uri: Uri? = null,
    val displayName: String = "",
    val provider: TranscriptionProvider = TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
    val requestedModel: String = "",
    val status: String = DISPLAY_JOB_STATUS_QUEUED,
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
)

fun TranscriptionJob.toUiState(): TranscriptionJobState = TranscriptionJobState(
    id = id,
    timestamp = timestamp,
    uri = uri.toUri(),
    displayName = displayName,
    provider = decodeHistoricalTranscriptionProvider(providerName),
    requestedModel = requestedModel.orEmpty(),
    status = displayJobStatus(status),
    progress = progress,
    transcript = transcript,
    language = language,
    model = model,
    summary = summary,
    summaryProvider = summaryProviderName
        .takeIf(String::isNotBlank)
        ?.let(::decodeHistoricalSummarizerProvider),
    summaryModel = summaryModel,
    summaryStatus = displaySummaryStatus(summaryStatus),
    autoSummaryAttempted = autoSummaryAttempted,
    batchId = batchId.orEmpty(),
    historyId = historyId,
    error = error,
    summaryError = summaryError,
    isRunning = isRunning,
    isSummarizing = isSummarizing,
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

private val TranscriptionJob.isReadyForAutoSummary: Boolean
    get() = !hasSummaryBlockingState && transcript.isNotBlank() && summary.isBlank() && !autoSummaryAttempted

internal fun UiState.toTranscriptionQueueSettings(): TranscriptionQueueSettings = TranscriptionQueueSettings(
    provider = provider,
    customModelName = transcriptionCustomGeminiModelName,
    speakerDiarizationEnabled = provider.isGemini() && speakerDiarizationEnabled,
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
    private val summarizationRepository: TranscriptionSummarizationRepository,
    private val queueService: TranscriptionQueueService,
    private val validationService: ValidationService,
    private val summarizationService: SummarizationService,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    companion object {
        private val staleSummariesRecovered = AtomicBoolean(false)
    }

    private val _uiState = MutableStateFlow(
        UiState(status = appContext.getString(R.string.main_waiting_for_audios)),
    )
    val uiState = _uiState.asStateFlow()
    private val summaryJobs = ConcurrentHashMap<Int, Job>()
    private val transcriptionSettingsHydrated = MutableStateFlow(false)
    private val settingsDelegate = MainSettingsDelegate(
        uiState = _uiState,
        settings = settings,
        validationService = validationService,
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
    )

    init {
        if (staleSummariesRecovered.compareAndSet(false, true)) {
            viewModelScope.launch {
                summarizationRepository.resetStaleSummaries()
            }
        }

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

                    // Auto-summarization logic
                    if (_uiState.value.automaticSummarizationEnabled &&
                        _uiState.value.geminiApiKeyState is ApiKeyState.Present
                    ) {
                        jobsList.forEach { job ->
                            if (job.isReadyForAutoSummary) {
                                runSummarization(job.id, allowResummarize = false)
                            }
                        }
                    }
                }
            }
        }

        viewModelScope.launch(ioDispatcher) {
            queueService.cleanupOldTempFiles(tempFileMaxAgeMs = TimeUnit.DAYS.toMillis(3))
            queueService.resumePendingQueue()
        }
    }

    fun dispatch(msg: MainMsg) {
        if (handleQueueMsg(msg)) return
        if (handleSettingsMsg(msg)) return
        reduce(msg)
    }

    private fun handleQueueMsg(msg: MainMsg): Boolean {
        when (msg) {
            is MainMsg.SharedAudioReceived -> enqueueSharedAudio(msg.uris)
            MainMsg.ClearCompletedJobs -> viewModelScope.launch { queueService.clearCompletedJobs() }
            is MainMsg.SummarizeJob -> runSummarization(msg.jobId, allowResummarize = msg.allowResummarize)
            is MainMsg.ClearSummary -> viewModelScope.launch { summarizationRepository.clearJobSummary(msg.jobId) }
            is MainMsg.ClearTranscript -> viewModelScope.launch { repository.clearJobTranscription(msg.jobId) }
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

    private fun enqueueSharedAudio(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            reduce(MainMsg.StatusChanged(appContext.getString(R.string.main_queuing_files, uris.size)))
            val queueSettings = resolveTranscriptionQueueSettingsForShare()
            val result = queueService.enqueueSharedAudio(
                uris = uris,
                provider = queueSettings.provider,
                customModelName = queueSettings.customModelName,
                speakerDiarizationEnabled = queueSettings.speakerDiarizationEnabled,
                postTranscriptionWorkSpec = resolveAutomaticSummarizationWorkSpecForQueue(),
            )
            reduce(MainMsg.StatusChanged(result.statusMessage))
        }
    }

    private fun retryJob(jobId: Int) {
        viewModelScope.launch {
            summaryJobs.remove(jobId)?.cancel()
            queueService.retryJob(
                jobId,
                _uiState.value.provider,
                _uiState.value.transcriptionCustomGeminiModelName,
                resolveAutomaticSummarizationWorkSpecForQueue(),
            )
        }
    }

    private fun runSummarization(jobId: Int, allowResummarize: Boolean) {
        summaryJobs[jobId]?.cancel()
        summaryJobs[jobId] = viewModelScope.launch {
            val currentState = _uiState.value
            val result = summarizationService.summarize(
                jobId = jobId,
                settings = currentState.toSummarizationSettings(),
                allowResummarize = allowResummarize,
                fallbackStatusMessage = currentState.status,
            )
            reduce(MainMsg.StatusChanged(result.statusMessage))
        }.also { job ->
            job.invokeOnCompletion {
                summaryJobs.remove(jobId, job)
            }
        }
    }

    private fun cancelSummary(jobId: Int) {
        summaryJobs.remove(jobId)?.cancel()
        viewModelScope.launch {
            val statusMessage = summarizationService.cancelSummary(jobId)
            reduce(MainMsg.StatusChanged(statusMessage))
        }
    }

    private fun reduce(msg: MainMsg) {
        _uiState.update { state -> MainReducer.reduce(state, msg) }
    }

    override fun onCleared() {
        summaryJobs.values.forEach(Job::cancel)
        summaryJobs.clear()
        super.onCleared()
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
