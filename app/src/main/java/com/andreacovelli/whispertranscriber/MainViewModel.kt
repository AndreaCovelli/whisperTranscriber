package com.andreacovelli.whispertranscriber

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class UiState(
    val status: String = "Waiting for audios...",
    val provider: TranscriptionProvider = TranscriptionProvider.ELEVEN_LABS,
    val summarizerProvider: TranscriptionProvider = TranscriptionProvider.GEMINI_FLASH,
    val elevenLabsApiKeyState: ApiKeyState = ApiKeyState.Missing,
    val elevenLabsKeyValidation: KeyValidationState = KeyValidationState.Idle,
    val geminiApiKeyState: ApiKeyState = ApiKeyState.Missing,
    val geminiKeyValidation: KeyValidationState = KeyValidationState.Idle,
    val transcriptionCustomGeminiModelName: String = "",
    val summarizerCustomGeminiModelName: String = "",
    val transcriptionCustomModelValidation: CustomModelValidation = CustomModelValidation.Idle,
    val summarizerCustomModelValidation: CustomModelValidation = CustomModelValidation.Idle,
    val automaticSummarizationEnabled: Boolean = false,
    val speakerDiarizationEnabled: Boolean = false,
    val jobs: List<TranscriptionJobState> = emptyList(),
)

data class TranscriptionJobState(
    val id: Int = 0,
    val uri: Uri? = null,
    val displayName: String = "",
    val provider: TranscriptionProvider = TranscriptionProvider.ELEVEN_LABS,
    val requestedModel: String = "",
    val status: String = "Queued",
    val progress: Float = 0f, // 0..1 conversion progress
    val transcript: String = "",
    val language: String = "",
    val model: String = "",
    val summary: String = "",
    val summaryModel: String = "",
    val batchId: String = "",
    val historyId: Int? = null,
    val error: String? = null,
    val summaryError: String? = null,
    val isRunning: Boolean = false,
    val isSummarizing: Boolean = false,
)

fun TranscriptionJob.toUiState(): TranscriptionJobState = TranscriptionJobState(
    id = id,
    uri = uri.toUri(),
    displayName = displayName,
    provider = runCatching { TranscriptionProvider.valueOf(providerName) }
        .getOrDefault(TranscriptionProvider.ELEVEN_LABS),
    requestedModel = requestedModel.orEmpty(),
    status = status,
    progress = progress,
    transcript = transcript,
    language = language,
    model = model,
    summary = summary,
    summaryModel = summaryModel,
    batchId = batchId.orEmpty(),
    historyId = historyId,
    error = error,
    summaryError = summaryError,
    isRunning = isRunning,
    isSummarizing = isSummarizing,
)

private val TranscriptionJob.hasSummaryBlockingState: Boolean
    get() = isRunning || isSummarizing || error != null || summaryError != null

private val TranscriptionJob.isReadyForAutoSummary: Boolean
    get() = !hasSummaryBlockingState && transcript.isNotBlank() && summary.isBlank()

data class TranscriptionData(val text: String, val language: String, val model: String)

sealed interface TranscriptionResult {
    data class Loading(val message: String) : TranscriptionResult
    data class Success(val data: TranscriptionData) : TranscriptionResult
    data class Error(val exception: Exception) : TranscriptionResult
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: ApiKeyManager,
    private val repository: TranscriptionRepository,
    private val queueService: TranscriptionQueueService,
    private val validationService: GeminiValidationService,
    private val summarizationService: SummarizationService,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    companion object {
        private val staleSummariesRecovered = AtomicBoolean(false)
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    val mainScreenActions = createMainScreenActions(
        clearCompletedJobs = {
            viewModelScope.launch {
                queueService.clearCompletedJobs()
            }
        },
        summarizeJob = { jobId -> runSummarization(jobId, allowResummarize = true) },
        clearSummary = { jobId ->
            viewModelScope.launch {
                repository.clearJobSummary(jobId)
            }
        },
        clearTranscript = { jobId ->
            viewModelScope.launch {
                repository.clearJobTranscription(jobId)
            }
        },
        retryJob = { jobId ->
            viewModelScope.launch {
                queueService.retryJob(jobId)
            }
        },
        cancelJob = { jobId ->
            viewModelScope.launch {
                queueService.cancelJob(jobId)
            }
        },
        deleteJob = { jobId ->
            viewModelScope.launch {
                queueService.deleteJob(jobId)
            }
        },
    )

    val settingsScreenActions = createSettingsScreenActions()

    init {
        if (staleSummariesRecovered.compareAndSet(false, true)) {
            viewModelScope.launch {
                repository.resetStaleSummaries()
            }
        }

        viewModelScope.launch {
            launch {
                settings.providerFlow.collect { p ->
                    _uiState.update { it.copy(provider = p) }
                }
            }
            launch {
                settings.summarizerProviderFlow.collect { p ->
                    _uiState.update { it.copy(summarizerProvider = p) }
                }
            }
            launch {
                settings.elevenLabsApiKeyStateFlow.collect { state ->
                    _uiState.update { it.copy(elevenLabsApiKeyState = state) }
                }
            }
            launch {
                settings.geminiApiKeyStateFlow.collect { state ->
                    _uiState.update { it.copy(geminiApiKeyState = state) }
                }
            }
            launch {
                settings.transcriptionCustomGeminiModelNameFlow.collect { name ->
                    _uiState.update { it.copy(transcriptionCustomGeminiModelName = name.orEmpty()) }
                }
            }
            launch {
                settings.summarizerCustomGeminiModelNameFlow.collect { name ->
                    _uiState.update { it.copy(summarizerCustomGeminiModelName = name.orEmpty()) }
                }
            }
            launch {
                settings.automaticSummarizationFlow.collect { enabled ->
                    _uiState.update { it.copy(automaticSummarizationEnabled = enabled) }
                }
            }
            launch {
                settings.speakerDiarizationFlow.collect { enabled ->
                    _uiState.update { it.copy(speakerDiarizationEnabled = enabled) }
                }
            }
            launch {
                repository.jobsState.collect { jobsList ->
                    _uiState.update { it.copy(jobs = jobsList.map { it.toUiState() }) }

                    // Auto-summarization logic
                    if (_uiState.value.automaticSummarizationEnabled &&
                        _uiState.value.geminiApiKeyState is ApiKeyState.Present
                    ) {
                        jobsList.forEach { job ->
                            if (job.isReadyForAutoSummary) {
                                requestAutoSummary(job.id)
                            }
                        }
                    }
                }
            }
        }

        viewModelScope.launch(ioDispatcher) {
            queueService.cleanupOldTempFiles(tempFileMaxAgeMs = TimeUnit.DAYS.toMillis(3))
        }
    }

    fun setElevenLabsKey(k: String) {
        val trimmed = k.trim()
        if (trimmed.isBlank()) return
        _uiState.update { it.copy(elevenLabsKeyValidation = KeyValidationState.Validating) }
        viewModelScope.launch {
            val result = validationService.validateElevenLabsKey(trimmed)
            if (result.isValid) {
                settings.saveElevenLabsApiKey(trimmed)
                _uiState.update { it.copy(elevenLabsKeyValidation = KeyValidationState.Idle) }
            } else {
                _uiState.update {
                    it.copy(
                        elevenLabsKeyValidation = KeyValidationState.Error(result.errorMessage ?: "Validation failed"),
                    )
                }
            }
        }
    }

    fun setGeminiKey(k: String) {
        val trimmed = k.trim()
        if (trimmed.isBlank()) return
        _uiState.update { it.copy(geminiKeyValidation = KeyValidationState.Validating) }
        viewModelScope.launch {
            val result = validationService.validateGeminiKey(trimmed)
            if (result.isValid) {
                settings.saveGeminiApiKey(trimmed)
                _uiState.update { it.copy(geminiKeyValidation = KeyValidationState.Idle) }
            } else {
                _uiState.update {
                    it.copy(geminiKeyValidation = KeyValidationState.Error(result.errorMessage ?: "Validation failed"))
                }
            }
        }
    }

    internal fun updateProviderSelection(provider: TranscriptionProvider) {
        _uiState.update { it.copy(provider = provider) }
        viewModelScope.launch {
            settings.saveProvider(provider)
        }
    }

    internal fun updateSummarizerProviderSelection(provider: TranscriptionProvider) {
        _uiState.update { it.copy(summarizerProvider = provider) }
        viewModelScope.launch {
            settings.saveSummarizerProvider(provider)
        }
    }

    internal fun updateAutomaticSummarization(enabled: Boolean) {
        _uiState.update { it.copy(automaticSummarizationEnabled = enabled) }
        viewModelScope.launch {
            settings.saveAutomaticSummarization(enabled)
        }
    }

    internal fun updateSpeakerDiarization(enabled: Boolean) {
        _uiState.update { it.copy(speakerDiarizationEnabled = enabled) }
        viewModelScope.launch {
            settings.saveSpeakerDiarization(enabled)
        }
    }

    internal fun resetTranscriptionModelValidation() {
        _uiState.update { it.copy(transcriptionCustomModelValidation = CustomModelValidation.Idle) }
    }

    internal fun resetSummarizerModelValidation() {
        _uiState.update { it.copy(summarizerCustomModelValidation = CustomModelValidation.Idle) }
    }

    internal fun resetElevenLabsKeyValidation() {
        _uiState.update { it.copy(elevenLabsKeyValidation = KeyValidationState.Idle) }
    }

    internal fun resetGeminiKeyValidation() {
        _uiState.update { it.copy(geminiKeyValidation = KeyValidationState.Idle) }
    }

    internal fun forgetElevenLabsKey() {
        viewModelScope.launch {
            settings.forgetElevenLabsApiKey()
            _uiState.update { it.copy(elevenLabsKeyValidation = KeyValidationState.Idle) }
        }
    }

    internal fun forgetGeminiKey() {
        viewModelScope.launch {
            settings.forgetGeminiApiKey()
            _uiState.update { it.copy(geminiKeyValidation = KeyValidationState.Idle) }
        }
    }

    fun validateTranscriptionGeminiModel(modelName: String) {
        validateCustomGeminiModel(modelName, isSummarizer = false)
    }

    fun validateSummarizerGeminiModel(modelName: String) {
        validateCustomGeminiModel(modelName, isSummarizer = true)
    }

    private fun validateCustomGeminiModel(modelName: String, isSummarizer: Boolean) {
        val trimmed = modelName.trim()
        if (trimmed.isBlank()) {
            updateModelValidationState(
                isSummarizer = isSummarizer,
                validation = CustomModelValidation.Error("Enter a model name"),
            )
            return
        }
        updateModelValidationState(isSummarizer = isSummarizer, validation = CustomModelValidation.Validating)

        viewModelScope.launch {
            val result = validationService.validateCustomModel(trimmed)
            if (result.isValid) {
                persistValidatedModel(trimmed, isSummarizer, result.displayName.orEmpty())
            } else {
                updateModelValidationState(
                    isSummarizer = isSummarizer,
                    validation = CustomModelValidation.Error(result.errorMessage ?: "Validation failed"),
                )
            }
        }
    }

    private suspend fun loadKeyForDisplay(keyProvider: suspend () -> CharArray?): String? = withContext(ioDispatcher) {
        val keyChars = keyProvider() ?: return@withContext null
        try {
            String(keyChars)
        } finally {
            keyChars.fill('\u0000')
        }
    }

    suspend fun loadElevenLabsKeyForDisplay(): String? = loadKeyForDisplay(settings::getElevenLabsApiKey)

    suspend fun loadGeminiKeyForDisplay(): String? = loadKeyForDisplay(settings::getGeminiApiKey)

    fun handleAudioShareMultiple(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(status = "Queuing ${uris.size} file(s)...") }
            val result = queueService.enqueueSharedAudio(
                uris = uris,
                provider = _uiState.value.provider,
                customModelName = _uiState.value.transcriptionCustomGeminiModelName,
                speakerDiarizationEnabled = _uiState.value.speakerDiarizationEnabled,
            )
            _uiState.update { it.copy(status = result.statusMessage) }
        }
    }

    private fun requestAutoSummary(jobId: Int) {
        runSummarization(jobId, allowResummarize = false)
    }

    private fun runSummarization(jobId: Int, allowResummarize: Boolean) {
        viewModelScope.launch {
            val result = summarizationService.summarize(jobId, _uiState.value, allowResummarize)
            _uiState.update { state ->
                state.copy(status = result.statusMessage)
            }
        }
    }

    private fun updateModelValidationState(isSummarizer: Boolean, validation: CustomModelValidation) {
        _uiState.update {
            if (isSummarizer) {
                it.copy(summarizerCustomModelValidation = validation)
            } else {
                it.copy(transcriptionCustomModelValidation = validation)
            }
        }
    }

    private suspend fun persistValidatedModel(modelName: String, isSummarizer: Boolean, displayName: String) {
        if (isSummarizer) {
            settings.saveSummarizerCustomGeminiModelName(modelName)
            _uiState.update {
                it.copy(
                    summarizerCustomGeminiModelName = modelName,
                    summarizerCustomModelValidation = CustomModelValidation.Valid(displayName),
                )
            }
        } else {
            settings.saveTranscriptionCustomGeminiModelName(modelName)
            _uiState.update {
                it.copy(
                    transcriptionCustomGeminiModelName = modelName,
                    transcriptionCustomModelValidation = CustomModelValidation.Valid(displayName),
                )
            }
        }
    }
}
