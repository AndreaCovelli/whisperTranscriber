package com.andreacovelli.whispertranscriber.ui.viewmodel

import com.andreacovelli.whispertranscriber.ApiKeyManager
import com.andreacovelli.whispertranscriber.CustomModelValidation
import com.andreacovelli.whispertranscriber.GeminiValidationService
import com.andreacovelli.whispertranscriber.KeyValidationState
import com.andreacovelli.whispertranscriber.SettingsScreenActions
import com.andreacovelli.whispertranscriber.TranscriptionProvider
import com.andreacovelli.whispertranscriber.UiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MainSettingsDelegate(
    private val uiState: MutableStateFlow<UiState>,
    private val settings: ApiKeyManager,
    private val validationService: GeminiValidationService,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    val actions = SettingsScreenActions(
        setProvider = ::updateProviderSelection,
        setSummarizerProvider = ::updateSummarizerProviderSelection,
        setAutomaticSummarization = ::updateAutomaticSummarization,
        setSpeakerDiarization = ::updateSpeakerDiarization,
        loadElevenLabsKey = { loadKeyForDisplay(settings::getElevenLabsApiKey) },
        loadGeminiKey = { loadKeyForDisplay(settings::getGeminiApiKey) },
        saveElevenLabsKey = ::setElevenLabsKey,
        saveGeminiKey = ::setGeminiKey,
        validateTranscriptionModel = { validateCustomGeminiModel(it, isSummarizer = false) },
        validateSummarizerModel = { validateCustomGeminiModel(it, isSummarizer = true) },
        resetTranscriptionModelValidation = ::resetTranscriptionModelValidation,
        resetSummarizerModelValidation = ::resetSummarizerModelValidation,
        resetElevenLabsKeyValidation = ::resetElevenLabsKeyValidation,
        resetGeminiKeyValidation = ::resetGeminiKeyValidation,
        forgetElevenLabsKey = ::forgetElevenLabsKey,
        forgetGeminiKey = ::forgetGeminiKey,
    )

    private fun setElevenLabsKey(key: String) {
        validateAndSaveApiKey(key = key, isGemini = false)
    }

    private fun setGeminiKey(key: String) {
        validateAndSaveApiKey(key = key, isGemini = true)
    }

    private fun validateAndSaveApiKey(key: String, isGemini: Boolean) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return

        updateApiKeyValidationState(isGemini = isGemini, validation = KeyValidationState.Validating)
        scope.launch {
            val result = if (isGemini) {
                validationService.validateGeminiKey(trimmed)
            } else {
                validationService.validateElevenLabsKey(trimmed)
            }
            if (result.isValid) {
                if (isGemini) {
                    settings.saveGeminiApiKey(trimmed)
                } else {
                    settings.saveElevenLabsApiKey(trimmed)
                }
                updateApiKeyValidationState(isGemini = isGemini, validation = KeyValidationState.Idle)
            } else {
                updateApiKeyValidationState(
                    isGemini = isGemini,
                    validation = KeyValidationState.Error(result.errorMessage ?: "Validation failed"),
                )
            }
        }
    }

    private fun updateProviderSelection(provider: TranscriptionProvider) {
        uiState.update { it.copy(provider = provider) }
        scope.launch {
            settings.saveProvider(provider)
        }
    }

    private fun updateSummarizerProviderSelection(provider: TranscriptionProvider) {
        uiState.update { it.copy(summarizerProvider = provider) }
        scope.launch {
            settings.saveSummarizerProvider(provider)
        }
    }

    private fun updateAutomaticSummarization(enabled: Boolean) {
        uiState.update { it.copy(automaticSummarizationEnabled = enabled) }
        scope.launch {
            settings.saveAutomaticSummarization(enabled)
        }
    }

    private fun updateSpeakerDiarization(enabled: Boolean) {
        uiState.update { it.copy(speakerDiarizationEnabled = enabled) }
        scope.launch {
            settings.saveSpeakerDiarization(enabled)
        }
    }

    private fun resetTranscriptionModelValidation() {
        updateModelValidationState(isSummarizer = false, validation = CustomModelValidation.Idle)
    }

    private fun resetSummarizerModelValidation() {
        updateModelValidationState(isSummarizer = true, validation = CustomModelValidation.Idle)
    }

    private fun resetElevenLabsKeyValidation() {
        updateApiKeyValidationState(isGemini = false, validation = KeyValidationState.Idle)
    }

    private fun resetGeminiKeyValidation() {
        updateApiKeyValidationState(isGemini = true, validation = KeyValidationState.Idle)
    }

    private fun forgetElevenLabsKey() {
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
                settings.forgetElevenLabsApiKey()
            }
            updateApiKeyValidationState(isGemini = isGemini, validation = KeyValidationState.Idle)
        }
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

        updateModelValidationState(
            isSummarizer = isSummarizer,
            validation = CustomModelValidation.Validating,
        )
        scope.launch {
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

    private fun updateApiKeyValidationState(isGemini: Boolean, validation: KeyValidationState) {
        uiState.update {
            if (isGemini) {
                it.copy(geminiKeyValidation = validation)
            } else {
                it.copy(elevenLabsKeyValidation = validation)
            }
        }
    }

    private fun updateModelValidationState(isSummarizer: Boolean, validation: CustomModelValidation) {
        uiState.update {
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
            uiState.update {
                it.copy(
                    summarizerCustomGeminiModelName = modelName,
                    summarizerCustomModelValidation = CustomModelValidation.Valid(displayName),
                )
            }
        } else {
            settings.saveTranscriptionCustomGeminiModelName(modelName)
            uiState.update {
                it.copy(
                    transcriptionCustomGeminiModelName = modelName,
                    transcriptionCustomModelValidation = CustomModelValidation.Valid(displayName),
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
}
