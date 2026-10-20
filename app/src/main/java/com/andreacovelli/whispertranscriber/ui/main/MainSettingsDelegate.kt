package com.andreacovelli.whispertranscriber.ui.main

import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.security.CredentialOperations
import com.andreacovelli.whispertranscriber.services.ValidationService
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun validationError(message: UserMessage?): UserMessage = message ?: UserMessage(R.string.validation_failed)

internal class MainSettingsDelegate(
    private val getState: () -> UiState,
    private val dispatch: (MainMsg) -> Unit,
    private val settings: ApiKeyManager,
    private val validationService: ValidationService,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val resolveMessage: (UserMessage) -> String,
    private val notify: (Int) -> Unit,
) {
    private val credentials = CredentialOperations()
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
        loadMicrosoftSpeechKey = { loadKeyForDisplay(false, settings::getMicrosoftSpeechApiKey) },
        loadGeminiKey = { loadKeyForDisplay(true, settings::getGeminiApiKey) },
        saveMicrosoftSpeechKey = { validateAndSaveKey(it, isGemini = false) },
        saveGeminiKey = { validateAndSaveKey(it, isGemini = true) },
        validateTranscriptionModel = { validateCustomGeminiModel(it, isSummarizer = false) },
        validateSummarizerModel = { validateCustomGeminiModel(it, isSummarizer = true) },
        resetTranscriptionModelValidation = ::resetTranscriptionModelValidation,
        resetSummarizerModelValidation = ::resetSummarizerModelValidation,
        resetMicrosoftSpeechKeyValidation = ::resetMicrosoftSpeechKeyValidation,
        resetGeminiKeyValidation = ::resetGeminiKeyValidation,
        forgetMicrosoftSpeechKey = ::forgetMicrosoftSpeechKey,
        forgetGeminiKey = ::forgetGeminiKey,
    )

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
                    validationService.validateMicrosoftSpeechKey(trimmed, getState().microsoftSpeechRegion)
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
                        KeyValidationState.Error(resolveMessage(validationError(result.errorMessage))),
                    )
                }
            }
        }
        if (isGemini) geminiValidationJob = job else microsoftValidationJob = job
    }

    private fun updateProviderSelection(provider: TranscriptionProvider) {
        launchSafely {
            settings.saveProvider(provider)
        }
    }

    private fun updateSummarizerProviderSelection(provider: TranscriptionProvider) {
        launchSafely {
            settings.saveSummarizerProvider(provider)
        }
    }

    private fun updateAutomaticSummarization(enabled: Boolean) {
        // Jobs still being transcribed pick this up; finished jobs are never summarized retroactively.
        launchSafely { settings.saveAutomaticSummarization(enabled) }
    }

    private fun updateBackgroundShareSummaryNotificationsEnabled(enabled: Boolean) {
        launchSafely {
            settings.saveBackgroundShareSummaryNotificationsEnabled(enabled)
        }
    }

    private fun updateSpeakerDiarization(enabled: Boolean) {
        launchSafely {
            settings.saveSpeakerDiarization(enabled)
        }
    }

    private fun updateMicrosoftSpeechRegion(region: String) {
        updateApiKeyValidationState(isGemini = false, validation = KeyValidationState.Idle)
        val trimmed = region.trim()
        microsoftRegionValidationJob?.cancel()

        if (trimmed.isBlank()) {
            updateRegionValidationState(RegionValidationState.Idle)
            launchSafely {
                settings.saveMicrosoftSpeechRegion("")
            }
            return
        }

        updateRegionValidationState(RegionValidationState.Validating)
        microsoftRegionValidationJob = launchSafely {
            val result = validationService.validateMicrosoftSpeechRegion(trimmed)
            if (result.isValid) {
                val normalizedRegion = result.normalizedRegion.orEmpty()
                settings.saveMicrosoftSpeechRegion(normalizedRegion)
                dispatch(MainMsg.PersistedMicrosoftSpeechRegion(normalizedRegion))
            } else {
                updateRegionValidationState(
                    RegionValidationState.Error(resolveMessage(validationError(result.errorMessage))),
                )
            }
        }
    }

    private fun resetTranscriptionModelValidation() {
        transcriptionModelValidationJob?.cancel()
        updateModelValidationState(isSummarizer = false, validation = CustomModelValidation.Idle)
    }

    private fun resetSummarizerModelValidation() {
        summarizerModelValidationJob?.cancel()
        updateModelValidationState(isSummarizer = true, validation = CustomModelValidation.Idle)
    }

    private fun resetMicrosoftSpeechKeyValidation() {
        credentials.supersede(false)
        microsoftValidationJob?.cancel()
        updateApiKeyValidationState(isGemini = false, validation = KeyValidationState.Idle)
    }

    private fun resetMicrosoftSpeechRegionValidation() {
        microsoftRegionValidationJob?.cancel()
        updateRegionValidationState(RegionValidationState.Idle)
    }

    private fun resetGeminiKeyValidation() {
        credentials.supersede(true)
        geminiValidationJob?.cancel()
        updateApiKeyValidationState(isGemini = true, validation = KeyValidationState.Idle)
    }

    private fun forgetMicrosoftSpeechKey() {
        forgetApiKey(isGemini = false)
    }

    private fun forgetGeminiKey() {
        forgetApiKey(isGemini = true)
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
                    notify(R.string.api_key_forgotten)
                }
            }
        }
    }

    private fun validateCustomGeminiModel(modelName: String, isSummarizer: Boolean) {
        val trimmed = modelName.trim()
        if (trimmed.isBlank()) {
            modelValidationJob(isSummarizer)?.cancel()
            updateModelValidationState(
                isSummarizer = isSummarizer,
                validation = CustomModelValidation.Error(
                    resolveMessage(UserMessage(R.string.custom_model_name_required)),
                ),
            )
            return
        }

        updateModelValidationState(
            isSummarizer = isSummarizer,
            validation = CustomModelValidation.Validating,
        )
        modelValidationJob(isSummarizer)?.cancel()
        val job = launchSafely {
            val result = validationService.validateCustomModel(trimmed)
            if (result.isValid) {
                persistValidatedModel(result.normalizedModelName ?: trimmed, isSummarizer, result.displayName.orEmpty())
            } else {
                updateModelValidationState(
                    isSummarizer = isSummarizer,
                    validation = CustomModelValidation.Error(resolveMessage(validationError(result.errorMessage))),
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
        notify(R.string.settings_persistence_failed)
        null
    }

    @Suppress("TooGenericExceptionCaught") // Keystore and DataStore failures are reported as a sanitized UI event.
    private fun launchSafely(
        onFailure: () -> Unit = {},
        block: suspend () -> Unit,
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            onFailure()
            notify(R.string.settings_persistence_failed)
        }
    }
}
