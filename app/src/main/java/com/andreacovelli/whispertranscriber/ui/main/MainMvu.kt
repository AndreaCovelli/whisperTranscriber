package com.andreacovelli.whispertranscriber.ui.main

import android.net.Uri
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.TranscriptionQueueSettings
import com.andreacovelli.whispertranscriber.ui.screens.BatchProgressState

sealed interface MainMsg {
    data class SharedAudioReceived(val uris: List<Uri>) : MainMsg
    data object ClearCompletedJobs : MainMsg
    data class SummarizeJob(val jobId: Int, val allowResummarize: Boolean = true) : MainMsg
    data class ClearSummary(val jobId: Int) : MainMsg
    data class ClearTranscript(val jobId: Int) : MainMsg
    data class RetryJob(val jobId: Int) : MainMsg
    data class CancelJob(val jobId: Int) : MainMsg
    data class CancelSummary(val jobId: Int) : MainMsg
    data class DeleteJob(val jobId: Int) : MainMsg

    data class SetProvider(val provider: TranscriptionProvider) : MainMsg
    data class SetSummarizerProvider(val provider: TranscriptionProvider) : MainMsg
    data class SetAutomaticSummarization(val enabled: Boolean) : MainMsg
    data class SetBackgroundShareSummaryNotificationsEnabled(val enabled: Boolean) : MainMsg
    data class SetSpeakerDiarization(val enabled: Boolean) : MainMsg
    data class SetMicrosoftSpeechRegion(val region: String) : MainMsg
    data class SaveMicrosoftSpeechKey(val key: String) : MainMsg
    data class SaveGeminiKey(val key: String) : MainMsg
    data class ValidateTranscriptionModel(val modelName: String) : MainMsg
    data class ValidateSummarizerModel(val modelName: String) : MainMsg
    data object ResetMicrosoftSpeechRegionValidation : MainMsg
    data object ResetTranscriptionModelValidation : MainMsg
    data object ResetSummarizerModelValidation : MainMsg
    data object ResetMicrosoftSpeechKeyValidation : MainMsg
    data object ResetGeminiKeyValidation : MainMsg
    data object ForgetMicrosoftSpeechKey : MainMsg
    data object ForgetGeminiKey : MainMsg

    data class StatusChanged(val status: String) : MainMsg
    data class TranscriptionSettingsLoaded(val settings: TranscriptionQueueSettings) : MainMsg
    data class SummarizerProviderLoaded(val provider: TranscriptionProvider) : MainMsg
    data class MicrosoftSpeechApiKeyStateChanged(val state: ApiKeyState) : MainMsg
    data class GeminiApiKeyStateChanged(val state: ApiKeyState) : MainMsg
    data class MicrosoftSpeechRegionLoaded(val region: String) : MainMsg
    data class SummarizerCustomModelLoaded(val modelName: String) : MainMsg
    data class AutomaticSummarizationLoaded(val enabled: Boolean) : MainMsg
    data class BackgroundShareSummaryNotificationsLoaded(val enabled: Boolean) : MainMsg
    data class JobPresentationChanged(
        val jobs: List<TranscriptionJobState>,
        val batchProgressState: BatchProgressState?,
    ) : MainMsg

    data class ApiKeyValidationStateChanged(val isGemini: Boolean, val validation: KeyValidationState) : MainMsg
    data class RegionValidationStateChanged(val validation: RegionValidationState) : MainMsg
    data class ModelValidationStateChanged(val isSummarizer: Boolean, val validation: CustomModelValidation) : MainMsg
    data class ValidatedModelPersisted(val modelName: String, val isSummarizer: Boolean, val displayName: String) :
        MainMsg
    data class PersistedMicrosoftSpeechRegion(val region: String) : MainMsg
}

internal object MainReducer {
    fun reduce(state: UiState, msg: MainMsg): UiState = when (msg) {
        is MainMsg.StatusChanged -> state.copy(status = msg.status)
        is MainMsg.TranscriptionSettingsLoaded -> state.copy(
            provider = msg.settings.provider,
            transcriptionCustomGeminiModelName = msg.settings.customModelName,
            speakerDiarizationEnabled = msg.settings.speakerDiarizationEnabled,
        )
        is MainMsg.SummarizerProviderLoaded -> state.copy(summarizerProvider = msg.provider)
        is MainMsg.MicrosoftSpeechApiKeyStateChanged -> state.copy(microsoftSpeechApiKeyState = msg.state)
        is MainMsg.GeminiApiKeyStateChanged -> state.copy(geminiApiKeyState = msg.state)
        is MainMsg.MicrosoftSpeechRegionLoaded -> state.copy(microsoftSpeechRegion = msg.region)
        is MainMsg.SummarizerCustomModelLoaded -> state.copy(summarizerCustomGeminiModelName = msg.modelName)
        is MainMsg.AutomaticSummarizationLoaded -> state.copy(automaticSummarizationEnabled = msg.enabled)
        is MainMsg.BackgroundShareSummaryNotificationsLoaded ->
            state.copy(backgroundShareSummaryNotificationsEnabled = msg.enabled)
        is MainMsg.JobPresentationChanged -> state.copy(
            jobs = msg.jobs,
            batchProgressState = msg.batchProgressState,
        )
        is MainMsg.SetProvider,
        is MainMsg.SetSummarizerProvider,
        is MainMsg.SetAutomaticSummarization,
        is MainMsg.SetBackgroundShareSummaryNotificationsEnabled,
        is MainMsg.SetSpeakerDiarization,
        is MainMsg.SetMicrosoftSpeechRegion,
        -> reduceSettings(state, msg)
        MainMsg.ResetMicrosoftSpeechRegionValidation,
        MainMsg.ResetTranscriptionModelValidation,
        MainMsg.ResetSummarizerModelValidation,
        MainMsg.ResetMicrosoftSpeechKeyValidation,
        MainMsg.ResetGeminiKeyValidation,
        MainMsg.ForgetMicrosoftSpeechKey,
        MainMsg.ForgetGeminiKey,
        is MainMsg.ApiKeyValidationStateChanged,
        is MainMsg.RegionValidationStateChanged,
        is MainMsg.ModelValidationStateChanged,
        is MainMsg.ValidatedModelPersisted,
        is MainMsg.PersistedMicrosoftSpeechRegion,
        -> reduceValidation(state, msg)
        else -> state
    }

    private fun reduceSettings(state: UiState, msg: MainMsg): UiState = when (msg) {
        is MainMsg.SetProvider -> state.copy(
            provider = msg.provider,
            speakerDiarizationEnabled = msg.provider.isGemini() && state.speakerDiarizationEnabled,
        )
        is MainMsg.SetSummarizerProvider -> state.copy(summarizerProvider = msg.provider)
        is MainMsg.SetAutomaticSummarization -> state.copy(automaticSummarizationEnabled = msg.enabled)
        is MainMsg.SetBackgroundShareSummaryNotificationsEnabled -> state.copy(
            backgroundShareSummaryNotificationsEnabled = msg.enabled,
        )
        is MainMsg.SetSpeakerDiarization -> state.copy(
            speakerDiarizationEnabled = state.provider.isGemini() && msg.enabled,
        )
        is MainMsg.SetMicrosoftSpeechRegion -> state.copy(
            microsoftSpeechRegion = msg.region,
            microsoftSpeechKeyValidation = KeyValidationState.Idle,
        )
        else -> state
    }

    private fun reduceValidation(state: UiState, msg: MainMsg): UiState = when (msg) {
        MainMsg.ResetMicrosoftSpeechRegionValidation -> state.copy(
            microsoftSpeechRegionValidation = RegionValidationState.Idle,
        )
        MainMsg.ResetTranscriptionModelValidation -> state.copy(
            transcriptionCustomModelValidation = CustomModelValidation.Idle,
        )
        MainMsg.ResetSummarizerModelValidation -> state.copy(
            summarizerCustomModelValidation = CustomModelValidation.Idle,
        )
        MainMsg.ResetMicrosoftSpeechKeyValidation -> state.copy(microsoftSpeechKeyValidation = KeyValidationState.Idle)
        MainMsg.ResetGeminiKeyValidation -> state.copy(geminiKeyValidation = KeyValidationState.Idle)
        MainMsg.ForgetMicrosoftSpeechKey -> state.copy(microsoftSpeechKeyValidation = KeyValidationState.Idle)
        MainMsg.ForgetGeminiKey -> state.copy(geminiKeyValidation = KeyValidationState.Idle)
        is MainMsg.ApiKeyValidationStateChanged -> if (msg.isGemini) {
            state.copy(geminiKeyValidation = msg.validation)
        } else {
            state.copy(microsoftSpeechKeyValidation = msg.validation)
        }
        is MainMsg.RegionValidationStateChanged -> state.copy(microsoftSpeechRegionValidation = msg.validation)
        is MainMsg.ModelValidationStateChanged -> if (msg.isSummarizer) {
            state.copy(summarizerCustomModelValidation = msg.validation)
        } else {
            state.copy(transcriptionCustomModelValidation = msg.validation)
        }
        is MainMsg.ValidatedModelPersisted -> if (msg.isSummarizer) {
            state.copy(
                summarizerCustomGeminiModelName = msg.modelName,
                summarizerCustomModelValidation = CustomModelValidation.Valid(msg.displayName),
            )
        } else {
            state.copy(
                transcriptionCustomGeminiModelName = msg.modelName,
                transcriptionCustomModelValidation = CustomModelValidation.Valid(msg.displayName),
            )
        }
        is MainMsg.PersistedMicrosoftSpeechRegion -> state.copy(
            microsoftSpeechRegion = msg.region,
            microsoftSpeechRegionValidation = RegionValidationState.Valid(msg.region),
        )
        else -> state
    }
}
