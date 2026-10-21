package com.andreacovelli.whispertranscriber.ui.settings

import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.TranscriptionQueueSettings

sealed class CustomModelValidation {
    object Idle : CustomModelValidation()
    object Validating : CustomModelValidation()
    data class Valid(val displayName: String) : CustomModelValidation()
    data class Error(val message: UserMessage) : CustomModelValidation()
}

sealed class KeyValidationState {
    object Idle : KeyValidationState()
    object Validating : KeyValidationState()
    data class Error(val message: UserMessage) : KeyValidationState()
}

sealed class RegionValidationState {
    object Idle : RegionValidationState()
    object Validating : RegionValidationState()
    data class Valid(val region: String) : RegionValidationState()
    data class Error(val message: UserMessage) : RegionValidationState()
}

data class SettingsUiState(
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
    val transcriptionLanguage: String = "",
    val transcriptionVocabulary: String = "",
)

/** User actions from the settings screen, plus the results the view model reports back. */
sealed interface SettingsMsg {
    data class SetProvider(val provider: TranscriptionProvider) : SettingsMsg
    data class SetSummarizerProvider(val provider: TranscriptionProvider) : SettingsMsg
    data class SetAutomaticSummarization(val enabled: Boolean) : SettingsMsg
    data class SetBackgroundShareSummaryNotificationsEnabled(val enabled: Boolean) : SettingsMsg
    data class SetSpeakerDiarization(val enabled: Boolean) : SettingsMsg
    data class SetTranscriptionLanguage(val languageCode: String) : SettingsMsg
    data class SetTranscriptionVocabulary(val vocabulary: String) : SettingsMsg
    data class SetMicrosoftSpeechRegion(val region: String) : SettingsMsg
    data class SaveMicrosoftSpeechKey(val key: String) : SettingsMsg
    data class SaveGeminiKey(val key: String) : SettingsMsg
    data class ValidateTranscriptionModel(val modelName: String) : SettingsMsg
    data class ValidateSummarizerModel(val modelName: String) : SettingsMsg
    data object ResetMicrosoftSpeechRegionValidation : SettingsMsg
    data object ResetTranscriptionModelValidation : SettingsMsg
    data object ResetSummarizerModelValidation : SettingsMsg
    data object ResetMicrosoftSpeechKeyValidation : SettingsMsg
    data object ResetGeminiKeyValidation : SettingsMsg
    data object ForgetMicrosoftSpeechKey : SettingsMsg
    data object ForgetGeminiKey : SettingsMsg

    /** Clears finished validation feedback so a reopened settings screen does not show stale results. */
    data object ClearValidationResults : SettingsMsg

    data class TranscriptionSettingsLoaded(val settings: TranscriptionQueueSettings) : SettingsMsg
    data class SummarizationSettingsLoaded(val settings: SummarizationSettings) : SettingsMsg
    data class MicrosoftSpeechApiKeyStateChanged(val state: ApiKeyState) : SettingsMsg
    data class GeminiApiKeyStateChanged(val state: ApiKeyState) : SettingsMsg
    data class MicrosoftSpeechRegionLoaded(val region: String) : SettingsMsg
    data class AutomaticSummarizationLoaded(val enabled: Boolean) : SettingsMsg
    data class BackgroundShareSummaryNotificationsLoaded(val enabled: Boolean) : SettingsMsg
    data class ApiKeyValidationStateChanged(val isGemini: Boolean, val validation: KeyValidationState) : SettingsMsg
    data class RegionValidationStateChanged(val validation: RegionValidationState) : SettingsMsg
    data class ModelValidationStateChanged(val isSummarizer: Boolean, val validation: CustomModelValidation) :
        SettingsMsg
    data class ValidatedModelPersisted(val modelName: String, val isSummarizer: Boolean, val displayName: String) :
        SettingsMsg
    data class PersistedMicrosoftSpeechRegion(val region: String) : SettingsMsg
}

/** Pure state transitions; persistence and validation run in [SettingsViewModel]. */
internal object SettingsReducer {
    fun reduce(state: SettingsUiState, msg: SettingsMsg): SettingsUiState = when (msg) {
        is SettingsMsg.SetProvider -> state.copy(provider = msg.provider)
        is SettingsMsg.SetSummarizerProvider -> state.copy(summarizerProvider = msg.provider)
        is SettingsMsg.SetAutomaticSummarization -> state.copy(automaticSummarizationEnabled = msg.enabled)
        is SettingsMsg.SetBackgroundShareSummaryNotificationsEnabled -> state.copy(
            backgroundShareSummaryNotificationsEnabled = msg.enabled,
        )
        is SettingsMsg.SetSpeakerDiarization -> state.copy(speakerDiarizationEnabled = msg.enabled)
        is SettingsMsg.SetTranscriptionLanguage -> state.copy(transcriptionLanguage = msg.languageCode)
        is SettingsMsg.SetTranscriptionVocabulary -> state.copy(transcriptionVocabulary = msg.vocabulary.trim())
        is SettingsMsg.SetMicrosoftSpeechRegion -> state.copy(
            microsoftSpeechRegion = msg.region,
            microsoftSpeechKeyValidation = KeyValidationState.Idle,
        )
        is SettingsMsg.SaveMicrosoftSpeechKey,
        is SettingsMsg.SaveGeminiKey,
        is SettingsMsg.ValidateTranscriptionModel,
        is SettingsMsg.ValidateSummarizerModel,
        -> state
        is SettingsMsg.TranscriptionSettingsLoaded -> state.copy(
            provider = msg.settings.provider,
            transcriptionCustomGeminiModelName = msg.settings.customModelName,
            speakerDiarizationEnabled = msg.settings.speakerDiarizationEnabled,
            transcriptionLanguage = msg.settings.languageHint,
            transcriptionVocabulary = msg.settings.vocabulary,
        )
        is SettingsMsg.SummarizationSettingsLoaded -> state.copy(
            summarizerProvider = msg.settings.provider,
            summarizerCustomGeminiModelName = msg.settings.customModelName,
        )
        is SettingsMsg.MicrosoftSpeechApiKeyStateChanged -> state.copy(microsoftSpeechApiKeyState = msg.state)
        is SettingsMsg.GeminiApiKeyStateChanged -> state.copy(geminiApiKeyState = msg.state)
        is SettingsMsg.MicrosoftSpeechRegionLoaded -> state.copy(microsoftSpeechRegion = msg.region)
        is SettingsMsg.AutomaticSummarizationLoaded -> state.copy(automaticSummarizationEnabled = msg.enabled)
        is SettingsMsg.BackgroundShareSummaryNotificationsLoaded ->
            state.copy(backgroundShareSummaryNotificationsEnabled = msg.enabled)
        SettingsMsg.ClearValidationResults -> clearValidationResults(state)
        else -> reduceValidation(state, msg)
    }

    // In-flight validations keep their state; their results still arrive and are shown.
    private fun clearValidationResults(state: SettingsUiState): SettingsUiState = state.copy(
        microsoftSpeechKeyValidation = state.microsoftSpeechKeyValidation.clearedResult(),
        microsoftSpeechRegionValidation = state.microsoftSpeechRegionValidation
            .takeIf { it is RegionValidationState.Validating } ?: RegionValidationState.Idle,
        geminiKeyValidation = state.geminiKeyValidation.clearedResult(),
        transcriptionCustomModelValidation = state.transcriptionCustomModelValidation.clearedResult(),
        summarizerCustomModelValidation = state.summarizerCustomModelValidation.clearedResult(),
    )

    private fun KeyValidationState.clearedResult(): KeyValidationState =
        takeIf { it is KeyValidationState.Validating } ?: KeyValidationState.Idle

    private fun CustomModelValidation.clearedResult(): CustomModelValidation =
        takeIf { it is CustomModelValidation.Validating } ?: CustomModelValidation.Idle

    private fun reduceValidation(state: SettingsUiState, msg: SettingsMsg): SettingsUiState = when (msg) {
        SettingsMsg.ResetMicrosoftSpeechRegionValidation -> state.copy(
            microsoftSpeechRegionValidation = RegionValidationState.Idle,
        )
        SettingsMsg.ResetTranscriptionModelValidation -> state.copy(
            transcriptionCustomModelValidation = CustomModelValidation.Idle,
        )
        SettingsMsg.ResetSummarizerModelValidation -> state.copy(
            summarizerCustomModelValidation = CustomModelValidation.Idle,
        )
        SettingsMsg.ResetMicrosoftSpeechKeyValidation,
        SettingsMsg.ForgetMicrosoftSpeechKey,
        -> state.copy(microsoftSpeechKeyValidation = KeyValidationState.Idle)
        SettingsMsg.ResetGeminiKeyValidation,
        SettingsMsg.ForgetGeminiKey,
        -> state.copy(geminiKeyValidation = KeyValidationState.Idle)
        is SettingsMsg.ApiKeyValidationStateChanged -> if (msg.isGemini) {
            state.copy(geminiKeyValidation = msg.validation)
        } else {
            state.copy(microsoftSpeechKeyValidation = msg.validation)
        }
        is SettingsMsg.RegionValidationStateChanged -> state.copy(microsoftSpeechRegionValidation = msg.validation)
        is SettingsMsg.ModelValidationStateChanged -> if (msg.isSummarizer) {
            state.copy(summarizerCustomModelValidation = msg.validation)
        } else {
            state.copy(transcriptionCustomModelValidation = msg.validation)
        }
        is SettingsMsg.ValidatedModelPersisted -> if (msg.isSummarizer) {
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
        is SettingsMsg.PersistedMicrosoftSpeechRegion -> state.copy(
            microsoftSpeechRegion = msg.region,
            microsoftSpeechRegionValidation = RegionValidationState.Valid(msg.region),
        )
        else -> state
    }
}
