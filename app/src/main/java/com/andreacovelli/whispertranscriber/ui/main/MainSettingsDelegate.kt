package com.andreacovelli.whispertranscriber.ui.main

import com.andreacovelli.whispertranscriber.services.ValidationService
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MainSettingsDelegate(
    private val getState: () -> UiState,
    private val dispatch: (MainMsg) -> Unit,
    private val settings: ApiKeyManager,
    private val validationService: ValidationService,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private var microsoftValidationJob: Job? = null
    private var geminiValidationJob: Job? = null
    private var microsoftRegionValidationJob: Job? = null
    private var transcriptionModelValidationJob: Job? = null
    private var summarizerModelValidationJob: Job? = null

    val actions = SettingsScreenActions(
        setProvider = ::updateProviderSelection,
        setSummarizerProvider = ::updateSummarizerProviderSelection,
        setAutomaticSummarization = ::updateAutomaticSummarization,
        setBackgroundShareSummaryNotificationsEnabled = ::updateBackgroundShareSummaryNotificationsEnabled,
        setSpeakerDiarization = ::updateSpeakerDiarization,
        setMicrosoftSpeechRegion = ::updateMicrosoftSpeechRegion,
        resetMicrosoftSpeechRegionValidation = ::resetMicrosoftSpeechRegionValidation,
        loadMicrosoftSpeechKey = { loadKeyForDisplay(settings::getMicrosoftSpeechApiKey) },
        loadGeminiKey = { loadKeyForDisplay(settings::getGeminiApiKey) },
        saveMicrosoftSpeechKey = ::validateAndSaveMicrosoftSpeechKey,
        saveGeminiKey = ::validateAndSaveGeminiKey,
        validateTranscriptionModel = { validateCustomGeminiModel(it, isSummarizer = false) },
        validateSummarizerModel = { validateCustomGeminiModel(it, isSummarizer = true) },
        resetTranscriptionModelValidation = ::resetTranscriptionModelValidation,
        resetSummarizerModelValidation = ::resetSummarizerModelValidation,
        resetMicrosoftSpeechKeyValidation = ::resetMicrosoftSpeechKeyValidation,
        resetGeminiKeyValidation = ::resetGeminiKeyValidation,
        forgetMicrosoftSpeechKey = ::forgetMicrosoftSpeechKey,
        forgetGeminiKey = ::forgetGeminiKey,
    )

    private fun validateAndSaveGeminiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return

        geminiValidationJob?.cancel()
        updateApiKeyValidationState(isGemini = true, validation = KeyValidationState.Validating)
        geminiValidationJob = scope.launch {
            val result = validationService.validateGeminiKey(trimmed)
            if (result.isValid) {
                settings.saveGeminiApiKey(trimmed)
                updateApiKeyValidationState(isGemini = true, validation = KeyValidationState.Idle)
            } else {
                updateApiKeyValidationState(
                    isGemini = true,
                    validation = KeyValidationState.Error(result.errorMessage ?: "Validation failed"),
                )
            }
        }
    }

    private fun validateAndSaveMicrosoftSpeechKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return

        microsoftValidationJob?.cancel()
        updateApiKeyValidationState(isGemini = false, validation = KeyValidationState.Validating)
        microsoftValidationJob = scope.launch {
            val result = validationService.validateMicrosoftSpeechKey(
                rawKey = trimmed,
                region = getState().microsoftSpeechRegion,
            )
            if (result.isValid) {
                settings.saveMicrosoftSpeechApiKey(trimmed)
                updateApiKeyValidationState(isGemini = false, validation = KeyValidationState.Idle)
            } else {
                updateApiKeyValidationState(
                    isGemini = false,
                    validation = KeyValidationState.Error(result.errorMessage ?: "Validation failed"),
                )
            }
        }
    }

    private fun updateProviderSelection(provider: TranscriptionProvider) {
        scope.launch {
            settings.saveProvider(provider)
            if (!provider.isGemini()) {
                settings.saveSpeakerDiarization(false)
            }
        }
    }

    private fun updateSummarizerProviderSelection(provider: TranscriptionProvider) {
        scope.launch {
            settings.saveSummarizerProvider(provider)
        }
    }

    private fun updateAutomaticSummarization(enabled: Boolean) {
        scope.launch {
            settings.saveAutomaticSummarization(enabled)
        }
    }

    private fun updateBackgroundShareSummaryNotificationsEnabled(enabled: Boolean) {
        scope.launch {
            settings.saveBackgroundShareSummaryNotificationsEnabled(enabled)
        }
    }

    private fun updateSpeakerDiarization(enabled: Boolean) {
        val normalized = getState().provider.isGemini() && enabled
        scope.launch {
            settings.saveSpeakerDiarization(normalized)
        }
    }

    private fun updateMicrosoftSpeechRegion(region: String) {
        updateApiKeyValidationState(isGemini = false, validation = KeyValidationState.Idle)
        val trimmed = region.trim()
        microsoftRegionValidationJob?.cancel()

        if (trimmed.isBlank()) {
            updateRegionValidationState(RegionValidationState.Idle)
            scope.launch {
                settings.saveMicrosoftSpeechRegion("")
            }
            return
        }

        updateRegionValidationState(RegionValidationState.Validating)
        microsoftRegionValidationJob = scope.launch {
            val result = validationService.validateMicrosoftSpeechRegion(trimmed)
            if (result.isValid) {
                val normalizedRegion = result.normalizedRegion.orEmpty()
                settings.saveMicrosoftSpeechRegion(normalizedRegion)
                dispatch(MainMsg.PersistedMicrosoftSpeechRegion(normalizedRegion))
            } else {
                updateRegionValidationState(
                    RegionValidationState.Error(result.errorMessage ?: "Validation failed"),
                )
            }
        }
    }

    private fun resetTranscriptionModelValidation() {
        updateModelValidationState(isSummarizer = false, validation = CustomModelValidation.Idle)
    }

    private fun resetSummarizerModelValidation() {
        updateModelValidationState(isSummarizer = true, validation = CustomModelValidation.Idle)
    }

    private fun resetMicrosoftSpeechKeyValidation() {
        updateApiKeyValidationState(isGemini = false, validation = KeyValidationState.Idle)
    }

    private fun resetMicrosoftSpeechRegionValidation() {
        microsoftRegionValidationJob?.cancel()
        updateRegionValidationState(RegionValidationState.Idle)
    }

    private fun resetGeminiKeyValidation() {
        updateApiKeyValidationState(isGemini = true, validation = KeyValidationState.Idle)
    }

    private fun forgetMicrosoftSpeechKey() {
        forgetApiKey(isGemini = false)
    }

    private fun forgetGeminiKey() {
        forgetApiKey(isGemini = true)
    }

    private fun forgetApiKey(isGemini: Boolean) {
        scope.launch {
            if (isGemini) {
                settings.forgetGeminiApiKey()
            } else {
                settings.forgetMicrosoftSpeechApiKey()
            }
            updateApiKeyValidationState(isGemini = isGemini, validation = KeyValidationState.Idle)
        }
    }

    private fun validateCustomGeminiModel(modelName: String, isSummarizer: Boolean) {
        val trimmed = modelName.trim()
        if (trimmed.isBlank()) {
            modelValidationJob(isSummarizer)?.cancel()
            updateModelValidationState(
                isSummarizer = isSummarizer,
                validation = CustomModelValidation.Error("Enter a model name"),
            )
            return
        }

        updateModelValidationState(
            isSummarizer = isSummarizer,
            validation = CustomModelValidation.Validating,
        )
        modelValidationJob(isSummarizer)?.cancel()
        val job = scope.launch {
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
        assignModelValidationJob(isSummarizer, job)
    }

    private fun modelValidationJob(isSummarizer: Boolean): Job? = if (isSummarizer) {
        summarizerModelValidationJob
    } else {
        transcriptionModelValidationJob
    }

    private fun assignModelValidationJob(isSummarizer: Boolean, job: Job) {
        if (isSummarizer) {
            summarizerModelValidationJob = job
        } else {
            transcriptionModelValidationJob = job
        }
    }

    private fun updateApiKeyValidationState(isGemini: Boolean, validation: KeyValidationState) {
        dispatch(MainMsg.ApiKeyValidationStateChanged(isGemini, validation))
    }

    private fun updateRegionValidationState(validation: RegionValidationState) {
        dispatch(MainMsg.RegionValidationStateChanged(validation))
    }

    private fun updateModelValidationState(isSummarizer: Boolean, validation: CustomModelValidation) {
        dispatch(MainMsg.ModelValidationStateChanged(isSummarizer, validation))
    }

    private suspend fun persistValidatedModel(modelName: String, isSummarizer: Boolean, displayName: String) {
        if (isSummarizer) {
            settings.saveSummarizerCustomGeminiModelName(modelName)
            dispatch(MainMsg.ValidatedModelPersisted(modelName, isSummarizer, displayName))
        } else {
            settings.saveTranscriptionCustomGeminiModelName(modelName)
            dispatch(MainMsg.ValidatedModelPersisted(modelName, isSummarizer, displayName))
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
}
