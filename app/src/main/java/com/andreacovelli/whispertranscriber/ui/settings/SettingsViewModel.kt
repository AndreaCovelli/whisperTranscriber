package com.andreacovelli.whispertranscriber.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.security.CredentialOperations
import com.andreacovelli.whispertranscriber.services.ValidationService
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun validationError(message: UserMessage?): UserMessage = message ?: UserMessage(R.string.validation_failed)

/** Owns the settings screen: persisted preferences, API keys, and their validation. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: ApiKeyManager,
    private val validationService: ValidationService,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private val _events = Channel<Int>(Channel.BUFFERED)

    /** One-off string resources to show as toasts. */
    val events: Flow<Int> = _events.receiveAsFlow()

    private val credentials = CredentialOperations()
    private var microsoftValidationJob: Job? = null
    private var geminiValidationJob: Job? = null
    private var microsoftRegionValidationJob: Job? = null
    private var transcriptionModelValidationJob: Job? = null
    private var summarizerModelValidationJob: Job? = null

    init {
        observe(settings.transcriptionQueueSettingsFlow, SettingsMsg::TranscriptionSettingsLoaded)
        observe(settings.summarizationSettingsFlow, SettingsMsg::SummarizationSettingsLoaded)
        observe(settings.microsoftSpeechApiKeyStateFlow, SettingsMsg::MicrosoftSpeechApiKeyStateChanged)
        observe(settings.geminiApiKeyStateFlow, SettingsMsg::GeminiApiKeyStateChanged)
        observe(settings.microsoftSpeechRegionFlow, SettingsMsg::MicrosoftSpeechRegionLoaded)
        observe(settings.automaticSummarizationFlow, SettingsMsg::AutomaticSummarizationLoaded)
        observe(
            settings.backgroundShareSummaryNotificationsEnabledFlow,
            SettingsMsg::BackgroundShareSummaryNotificationsLoaded,
        )
    }

    fun dispatch(msg: SettingsMsg) {
        reduce(msg)
        when (msg) {
            is SettingsMsg.SetProvider -> launchSafely { settings.saveProvider(msg.provider) }
            is SettingsMsg.SetSummarizerProvider -> launchSafely { settings.saveSummarizerProvider(msg.provider) }
            // Jobs still being transcribed pick this up; finished jobs are never summarized retroactively.
            is SettingsMsg.SetAutomaticSummarization ->
                launchSafely { settings.saveAutomaticSummarization(msg.enabled) }
            is SettingsMsg.SetBackgroundShareSummaryNotificationsEnabled ->
                launchSafely { settings.saveBackgroundShareSummaryNotificationsEnabled(msg.enabled) }
            is SettingsMsg.SetSpeakerDiarization -> launchSafely { settings.saveSpeakerDiarization(msg.enabled) }
            is SettingsMsg.SetTranscriptionLanguage ->
                launchSafely { settings.saveTranscriptionLanguage(msg.languageCode) }
            is SettingsMsg.SetTranscriptionVocabulary ->
                launchSafely { settings.saveTranscriptionVocabulary(msg.vocabulary) }
            is SettingsMsg.SetMicrosoftSpeechRegion -> updateMicrosoftSpeechRegion(msg.region)
            is SettingsMsg.SaveMicrosoftSpeechKey -> validateAndSaveKey(msg.key, isGemini = false)
            is SettingsMsg.SaveGeminiKey -> validateAndSaveKey(msg.key, isGemini = true)
            is SettingsMsg.ValidateTranscriptionModel -> validateCustomGeminiModel(msg.modelName, isSummarizer = false)
            is SettingsMsg.ValidateSummarizerModel -> validateCustomGeminiModel(msg.modelName, isSummarizer = true)
            SettingsMsg.ResetMicrosoftSpeechRegionValidation -> microsoftRegionValidationJob?.cancel()
            SettingsMsg.ResetTranscriptionModelValidation -> transcriptionModelValidationJob?.cancel()
            SettingsMsg.ResetSummarizerModelValidation -> summarizerModelValidationJob?.cancel()
            SettingsMsg.ResetMicrosoftSpeechKeyValidation -> resetKeyValidation(isGemini = false)
            SettingsMsg.ResetGeminiKeyValidation -> resetKeyValidation(isGemini = true)
            SettingsMsg.ForgetMicrosoftSpeechKey -> forgetApiKey(isGemini = false)
            SettingsMsg.ForgetGeminiKey -> forgetApiKey(isGemini = true)
            else -> Unit
        }
    }

    suspend fun loadMicrosoftSpeechKeyForDisplay(): String? =
        loadKeyForDisplay(isGemini = false, keyProvider = settings::getMicrosoftSpeechApiKey)

    suspend fun loadGeminiKeyForDisplay(): String? =
        loadKeyForDisplay(isGemini = true, keyProvider = settings::getGeminiApiKey)

    private fun reduce(msg: SettingsMsg) = _uiState.update { state -> SettingsReducer.reduce(state, msg) }

    private fun <T> observe(flow: Flow<T>, toMsg: (T) -> SettingsMsg) {
        viewModelScope.launch { flow.collect { value -> reduce(toMsg(value)) } }
    }

    private fun validateAndSaveKey(key: String, isGemini: Boolean) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return
        val token = credentials.supersede(isGemini)
        updateApiKeyValidationState(isGemini, KeyValidationState.Validating)
        val job = launchSafely(onFailure = {
            if (credentials.isCurrent(token)) updateApiKeyValidationState(isGemini, KeyValidationState.Idle)
        }) {
            credentials.run(token) {
                val result = if (isGemini) {
                    validationService.validateGeminiKey(trimmed)
                } else {
                    validationService.validateMicrosoftSpeechKey(trimmed, _uiState.value.microsoftSpeechRegion)
                }
                if (!credentials.isCurrent(token)) return@run
                if (result.isValid) {
                    val persisted = credentials.persist(token) {
                        if (isGemini) {
                            settings.saveGeminiApiKey(
                                trimmed,
                            )
                        } else {
                            settings.saveMicrosoftSpeechApiKey(trimmed)
                        }
                    }
                    if (persisted) updateApiKeyValidationState(isGemini, KeyValidationState.Idle)
                } else {
                    updateApiKeyValidationState(
                        isGemini,
                        KeyValidationState.Error(validationError(result.errorMessage)),
                    )
                }
            }
        }
        if (isGemini) geminiValidationJob = job else microsoftValidationJob = job
    }

    private fun updateMicrosoftSpeechRegion(region: String) {
        val trimmed = region.trim()
        microsoftRegionValidationJob?.cancel()

        if (trimmed.isBlank()) {
            reduce(SettingsMsg.RegionValidationStateChanged(RegionValidationState.Idle))
            launchSafely { settings.saveMicrosoftSpeechRegion("") }
            return
        }

        reduce(SettingsMsg.RegionValidationStateChanged(RegionValidationState.Validating))
        microsoftRegionValidationJob = launchSafely {
            val result = validationService.validateMicrosoftSpeechRegion(trimmed)
            if (result.isValid) {
                val normalizedRegion = result.normalizedRegion.orEmpty()
                settings.saveMicrosoftSpeechRegion(normalizedRegion)
                reduce(SettingsMsg.PersistedMicrosoftSpeechRegion(normalizedRegion))
            } else {
                reduce(
                    SettingsMsg.RegionValidationStateChanged(
                        RegionValidationState.Error(validationError(result.errorMessage)),
                    ),
                )
            }
        }
    }

    private fun resetKeyValidation(isGemini: Boolean) {
        credentials.supersede(isGemini)
        if (isGemini) geminiValidationJob?.cancel() else microsoftValidationJob?.cancel()
    }

    private fun forgetApiKey(isGemini: Boolean) {
        val token = credentials.supersede(isGemini)
        launchSafely {
            credentials.run(token) {
                val forgotten = credentials.delete(token) {
                    if (isGemini) settings.forgetGeminiApiKey() else settings.forgetMicrosoftSpeechApiKey()
                }
                if (forgotten) {
                    updateApiKeyValidationState(isGemini, KeyValidationState.Idle)
                    _events.trySend(R.string.api_key_forgotten)
                }
            }
        }
    }

    private fun validateCustomGeminiModel(modelName: String, isSummarizer: Boolean) {
        val trimmed = modelName.trim()
        modelValidationJob(isSummarizer)?.cancel()
        if (trimmed.isBlank()) {
            updateModelValidationState(
                isSummarizer,
                CustomModelValidation.Error(UserMessage(R.string.custom_model_name_required)),
            )
            return
        }

        updateModelValidationState(isSummarizer, CustomModelValidation.Validating)
        val job = launchSafely {
            val result = validationService.validateCustomModel(trimmed)
            if (result.isValid) {
                val normalizedName = result.normalizedModelName ?: trimmed
                if (isSummarizer) {
                    settings.saveSummarizerCustomGeminiModelName(normalizedName)
                } else {
                    settings.saveTranscriptionCustomGeminiModelName(normalizedName)
                }
                reduce(SettingsMsg.ValidatedModelPersisted(normalizedName, isSummarizer, result.displayName.orEmpty()))
            } else {
                updateModelValidationState(
                    isSummarizer,
                    CustomModelValidation.Error(validationError(result.errorMessage)),
                )
            }
        }
        if (isSummarizer) summarizerModelValidationJob = job else transcriptionModelValidationJob = job
    }

    private fun modelValidationJob(isSummarizer: Boolean): Job? = if (isSummarizer) {
        summarizerModelValidationJob
    } else {
        transcriptionModelValidationJob
    }

    private fun updateApiKeyValidationState(isGemini: Boolean, validation: KeyValidationState) {
        reduce(SettingsMsg.ApiKeyValidationStateChanged(isGemini, validation))
    }

    private fun updateModelValidationState(isSummarizer: Boolean, validation: CustomModelValidation) {
        reduce(SettingsMsg.ModelValidationStateChanged(isSummarizer, validation))
    }

    @Suppress("TooGenericExceptionCaught") // Do not surface credential storage details in the UI.
    private suspend fun loadKeyForDisplay(isGemini: Boolean, keyProvider: suspend () -> CharArray?): String? = try {
        val token = credentials.current(isGemini)
        credentials.run(token) {
            withContext(ioDispatcher) {
                val keyChars = keyProvider() ?: return@withContext null
                try {
                    if (credentials.isCurrent(token)) String(keyChars) else null
                } finally {
                    keyChars.fill('\u0000')
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        _events.trySend(R.string.settings_persistence_failed)
        null
    }

    @Suppress("TooGenericExceptionCaught") // Keystore and DataStore failures are reported as a sanitized UI event.
    private fun launchSafely(
        onFailure: () -> Unit = {},
        block: suspend () -> Unit,
    ): Job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            onFailure()
            _events.trySend(R.string.settings_persistence_failed)
        }
    }
}
