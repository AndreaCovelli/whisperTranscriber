package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.TranscriptionQueueSettings
import com.andreacovelli.whispertranscriber.summary.SummaryStyle
import com.andreacovelli.whispertranscriber.ui.settings.CustomModelValidation
import com.andreacovelli.whispertranscriber.ui.settings.KeyValidationState
import com.andreacovelli.whispertranscriber.ui.settings.RegionValidationState
import com.andreacovelli.whispertranscriber.ui.settings.SettingsMsg
import com.andreacovelli.whispertranscriber.ui.settings.SettingsReducer
import com.andreacovelli.whispertranscriber.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsReducerTest {
    @Test
    fun loadedSettings_replaceTheProviderSelections() {
        val state = listOf(
            SettingsMsg.TranscriptionSettingsLoaded(
                TranscriptionQueueSettings(
                    provider = TranscriptionProvider.GEMINI_CUSTOM,
                    customModelName = "gemini-custom",
                    speakerDiarizationEnabled = true,
                    languageHint = "it",
                    vocabulary = "Covelli",
                ),
            ),
            SettingsMsg.SummarizationSettingsLoaded(
                SummarizationSettings(
                    provider = TranscriptionProvider.GEMINI_PRO,
                    customModelName = "summary-model",
                    style = SummaryStyle.MEETING_MINUTES,
                    customPrompt = "Be brief",
                ),
            ),
        ).fold(SettingsUiState(), SettingsReducer::reduce)

        assertEquals(TranscriptionProvider.GEMINI_CUSTOM, state.provider)
        assertEquals("gemini-custom", state.transcriptionCustomGeminiModelName)
        assertTrue(state.speakerDiarizationEnabled)
        assertEquals("it", state.transcriptionLanguage)
        assertEquals("Covelli", state.transcriptionVocabulary)
        assertEquals(TranscriptionProvider.GEMINI_PRO, state.summarizerProvider)
        assertEquals("summary-model", state.summarizerCustomGeminiModelName)
        assertEquals(SummaryStyle.MEETING_MINUTES, state.summaryStyle)
        assertEquals("Be brief", state.summaryCustomPrompt)
    }

    @Test
    fun summaryStyleEdits_updateStateWithANormalizedPrompt() {
        val state = listOf(
            SettingsMsg.SetSummaryStyle(SummaryStyle.CUSTOM),
            SettingsMsg.SetSummaryCustomPrompt("  List open questions \n"),
        ).fold(SettingsUiState(), SettingsReducer::reduce)

        assertEquals(SummaryStyle.CUSTOM, state.summaryStyle)
        assertEquals("List open questions", state.summaryCustomPrompt)
    }

    @Test
    fun changingTheRegion_clearsTheMicrosoftKeyValidation() {
        val state = SettingsReducer.reduce(
            SettingsUiState(microsoftSpeechKeyValidation = KeyValidationState.Error(UserMessage.verbatim("bad key"))),
            SettingsMsg.SetMicrosoftSpeechRegion("westeurope"),
        )

        assertEquals("westeurope", state.microsoftSpeechRegion)
        assertEquals(KeyValidationState.Idle, state.microsoftSpeechKeyValidation)
    }

    @Test
    fun persistedValidationResults_updateOnlyTheMatchingField() {
        val state = listOf(
            SettingsMsg.ValidatedModelPersisted("models/custom", isSummarizer = true, displayName = "Custom"),
            SettingsMsg.PersistedMicrosoftSpeechRegion("eastus"),
        ).fold(SettingsUiState(), SettingsReducer::reduce)

        assertEquals("models/custom", state.summarizerCustomGeminiModelName)
        assertEquals(CustomModelValidation.Valid("Custom"), state.summarizerCustomModelValidation)
        assertEquals(CustomModelValidation.Idle, state.transcriptionCustomModelValidation)
        assertEquals("", state.transcriptionCustomGeminiModelName)
        assertEquals(RegionValidationState.Valid("eastus"), state.microsoftSpeechRegionValidation)
    }

    @Test
    fun forgettingAKey_clearsItsValidationError() {
        val state = SettingsReducer.reduce(
            SettingsUiState(
                geminiKeyValidation = KeyValidationState.Error(UserMessage.verbatim("bad key")),
                microsoftSpeechKeyValidation = KeyValidationState.Error(UserMessage.verbatim("other key")),
            ),
            SettingsMsg.ForgetGeminiKey,
        )

        assertEquals(KeyValidationState.Idle, state.geminiKeyValidation)
        assertEquals(KeyValidationState.Error(UserMessage.verbatim("other key")), state.microsoftSpeechKeyValidation)
    }

    @Test
    fun clearingValidationResults_resetsFinishedResultsAndKeepsInFlightValidations() {
        val state = SettingsReducer.reduce(
            SettingsUiState(
                microsoftSpeechKeyValidation = KeyValidationState.Error(UserMessage.verbatim("bad key")),
                microsoftSpeechRegionValidation = RegionValidationState.Valid("eastus"),
                geminiKeyValidation = KeyValidationState.Validating,
                transcriptionCustomModelValidation = CustomModelValidation.Valid("Custom"),
                summarizerCustomModelValidation = CustomModelValidation.Validating,
            ),
            SettingsMsg.ClearValidationResults,
        )

        assertEquals(KeyValidationState.Idle, state.microsoftSpeechKeyValidation)
        assertEquals(RegionValidationState.Idle, state.microsoftSpeechRegionValidation)
        assertEquals(KeyValidationState.Validating, state.geminiKeyValidation)
        assertEquals(CustomModelValidation.Idle, state.transcriptionCustomModelValidation)
        assertEquals(CustomModelValidation.Validating, state.summarizerCustomModelValidation)
    }
}
