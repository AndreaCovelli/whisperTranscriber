package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.ui.components.providerDisplayLabel
import com.andreacovelli.whispertranscriber.ui.components.providerFamilyLabel
import com.andreacovelli.whispertranscriber.ui.components.providerFamilyLabelForModel
import com.andreacovelli.whispertranscriber.ui.components.shouldShowRetranscribeAction
import com.andreacovelli.whispertranscriber.ui.screens.BatchProgressState
import com.andreacovelli.whispertranscriber.ui.screens.buildBatchProgressState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionPresentationTest {
    @Test
    fun resolveShareQueueSettings_usesPersistedSettingsBeforeHydration() {
        val resolved = resolveShareQueueSettings(
            uiState = UiState(
                provider = TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
                transcriptionCustomGeminiModelName = "",
                speakerDiarizationEnabled = false,
            ),
            transcriptionSettingsHydrated = false,
            persistedSettings = TranscriptionQueueSettings(
                provider = TranscriptionProvider.GEMINI_FLASH,
                customModelName = "gemini-2.5-flash",
                speakerDiarizationEnabled = true,
            ),
        )

        assertEquals(
            TranscriptionQueueSettings(
                provider = TranscriptionProvider.GEMINI_FLASH,
                customModelName = "gemini-2.5-flash",
                speakerDiarizationEnabled = true,
            ),
            resolved,
        )
    }

    @Test
    fun resolveShareQueueSettings_prefersInMemoryStateAfterHydration() {
        val resolved = resolveShareQueueSettings(
            uiState = UiState(
                provider = TranscriptionProvider.GEMINI_CUSTOM,
                transcriptionCustomGeminiModelName = "gemini-live-custom",
                speakerDiarizationEnabled = true,
            ),
            transcriptionSettingsHydrated = true,
            persistedSettings = TranscriptionQueueSettings(
                provider = TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
                customModelName = "",
                speakerDiarizationEnabled = false,
            ),
        )

        assertEquals(
            TranscriptionQueueSettings(
                provider = TranscriptionProvider.GEMINI_CUSTOM,
                customModelName = "gemini-live-custom",
                speakerDiarizationEnabled = true,
            ),
            resolved,
        )
    }

    @Test
    fun uiStateToTranscriptionQueueSettings_preservesCurrentSelection() {
        assertEquals(
            TranscriptionQueueSettings(
                provider = TranscriptionProvider.GEMINI_PRO,
                customModelName = "ignored-for-built-in-model",
                speakerDiarizationEnabled = true,
            ),
            UiState(
                provider = TranscriptionProvider.GEMINI_PRO,
                transcriptionCustomGeminiModelName = "ignored-for-built-in-model",
                speakerDiarizationEnabled = true,
            ).toTranscriptionQueueSettings(),
        )
    }

    @Test
    fun uiStateToSummarizationSettings_preservesCurrentSelection() {
        assertEquals(
            SummarizationSettings(
                provider = TranscriptionProvider.GEMINI_CUSTOM,
                customModelName = "gemini-custom-summary",
            ),
            UiState(
                summarizerProvider = TranscriptionProvider.GEMINI_CUSTOM,
                summarizerCustomGeminiModelName = "gemini-custom-summary",
            ).toSummarizationSettings(),
        )
    }

    @Test
    fun postTranscriptionWorkSpec_roundTripsThroughWorkerInputData() {
        val spec = PostTranscriptionWorkSpec(
            summarizeAfterTranscription = true,
            notifyResult = true,
            summarizationSettings = SummarizationSettings(
                provider = TranscriptionProvider.GEMINI_CUSTOM,
                customModelName = "gemini-custom-summary",
            ),
        )

        val restored = PostTranscriptionWorkSpec.fromInputData(
            spec.toInputData(jobId = 42, speakerLabelPrefix = "Speaker"),
        )

        assertEquals(spec, restored)
    }

    @Test
    fun uiStateToAutomaticSummarizationWorkSpec_returnsNullWhenAutomaticSummarizationDisabled() {
        assertNull(
            UiState(
                automaticSummarizationEnabled = false,
                geminiApiKeyState = ApiKeyState.Present,
            ).toAutomaticSummarizationWorkSpec(),
        )
    }

    @Test
    fun uiStateToAutomaticSummarizationWorkSpec_returnsSilentWorkerSpecWhenEnabled() {
        assertEquals(
            PostTranscriptionWorkSpec(
                summarizeAfterTranscription = true,
                notifyResult = false,
                summarizationSettings = SummarizationSettings(
                    provider = TranscriptionProvider.GEMINI_CUSTOM,
                    customModelName = "gemini-custom-summary",
                ),
            ),
            UiState(
                automaticSummarizationEnabled = true,
                geminiApiKeyState = ApiKeyState.Present,
                summarizerProvider = TranscriptionProvider.GEMINI_CUSTOM,
                summarizerCustomGeminiModelName = "gemini-custom-summary",
            ).toAutomaticSummarizationWorkSpec(),
        )
    }

    @Test
    fun decodeStoredTranscriptionProvider_mapsLegacyGemini25FlashValue() {
        assertEquals(
            TranscriptionProvider.GEMINI_FLASH,
            decodeStoredTranscriptionProvider("GEMINI_25_FLASH"),
        )
    }

    @Test
    fun decodeStoredTranscriptionProvider_mapsLegacyElevenLabsSelectionToMicrosoft() {
        assertEquals(
            TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
            decodeStoredTranscriptionProvider("ELEVEN_LABS"),
        )
    }

    @Test
    fun decodeHistoricalTranscriptionProvider_preservesLegacyElevenLabsForDisplay() {
        assertEquals(
            TranscriptionProvider.ELEVEN_LABS,
            decodeHistoricalTranscriptionProvider("ELEVEN_LABS"),
        )
    }

    @Test
    fun providerFamilyLabel_returnsGeminiCustomForCustomProvider() {
        assertEquals("Gemini Custom", providerFamilyLabel(TranscriptionProvider.GEMINI_CUSTOM))
    }

    @Test
    fun providerFamilyLabel_returnsMicrosoftLabelForMicrosoftProvider() {
        assertEquals("Microsoft MAI Transcribe", providerFamilyLabel(TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE))
    }

    @Test
    fun providerFamilyLabelForModel_returnsKnownProviderLabelForBuiltInModel() {
        assertEquals("Gemini Flash", providerFamilyLabelForModel("gemini-flash-latest"))
    }

    @Test
    fun providerFamilyLabelForModel_classifiesVersionedFlashModelsAsGeminiFlash() {
        assertEquals("Gemini Flash", providerFamilyLabelForModel("gemini-2.5-flash-preview-04-17"))
    }

    @Test
    fun providerFamilyLabelForModel_classifiesVersionedFlashLiteModelsAsGeminiFlashLite() {
        assertEquals("Gemini Flash Lite", providerFamilyLabelForModel("gemini-2.0-flash-lite-preview"))
    }

    @Test
    fun providerFamilyLabelForModel_returnsGeminiCustomForUnknownModel() {
        assertEquals("Gemini Custom", providerFamilyLabelForModel("gemini-experimental-foo"))
    }

    @Test
    fun providerFamilyLabel_prefersExplicitCustomSummaryProviderOverModelHeuristics() {
        assertEquals(
            "Gemini Custom",
            providerFamilyLabel(TranscriptionProvider.GEMINI_CUSTOM, "gemini-2.0-flash-lite-preview"),
        )
    }

    @Test
    fun providerDisplayLabel_includesConfiguredCustomModelName() {
        assertEquals(
            "gemini-2.5-flash",
            providerDisplayLabel(TranscriptionProvider.GEMINI_CUSTOM, "gemini-2.5-flash"),
        )
    }

    @Test
    fun providerDisplayLabel_usesNotSetFallbackForBlankCustomModelName() {
        assertEquals(
            "Custom Gemini model (not set)",
            providerDisplayLabel(TranscriptionProvider.GEMINI_CUSTOM, ""),
        )
    }

    @Test
    fun uiStateToTranscriptionQueueSettings_disablesSpeakerDiarizationForMicrosoft() {
        assertEquals(
            TranscriptionQueueSettings(
                provider = TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
                customModelName = "",
                speakerDiarizationEnabled = false,
            ),
            UiState(
                provider = TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
                speakerDiarizationEnabled = true,
            ).toTranscriptionQueueSettings(),
        )
    }

    @Test
    fun buildBatchProgressState_returnsNullForSingleJob() {
        val jobs = listOf(
            testUiJob(id = 1, batchId = "batch-a", status = "Queued"),
        )

        assertNull(buildBatchProgressState(jobs))
    }

    @Test
    fun buildBatchProgressState_usesLatestActiveMultiFileBatch() {
        val jobs = listOf(
            testUiJob(id = 1, timestamp = 1_000L, batchId = "batch-old", status = "Completed"),
            testUiJob(id = 2, timestamp = 2_000L, batchId = "batch-old", status = "Completed"),
            testUiJob(id = 3, timestamp = 3_000L, batchId = "batch-new", status = "Completed"),
            testUiJob(id = 4, timestamp = 4_000L, batchId = "batch-new", status = "Transcribing", isRunning = true),
            testUiJob(id = 5, timestamp = 5_000L, batchId = "batch-new", status = "Queued"),
        )

        assertEquals(
            BatchProgressState(currentFileNumber = 2, totalFiles = 3, completedFiles = 1),
            buildBatchProgressState(jobs),
        )
    }

    @Test
    fun buildBatchProgressState_prefersRunningBatchOverNewerQueuedBatch() {
        val jobs = listOf(
            testUiJob(
                id = 50,
                timestamp = 1_000L,
                batchId = "batch-running",
                status = "Transcribing",
                isRunning = true,
            ),
            testUiJob(id = 51, timestamp = 1_000L, batchId = "batch-running", status = "Completed"),
            testUiJob(id = 10, timestamp = 2_000L, batchId = "batch-newer", status = "Queued"),
            testUiJob(id = 11, timestamp = 2_000L, batchId = "batch-newer", status = "Queued"),
        )

        assertEquals(
            BatchProgressState(currentFileNumber = 2, totalFiles = 2, completedFiles = 1),
            buildBatchProgressState(jobs),
        )
    }

    @Test
    fun buildBatchProgressState_prefersEarliestQueuedTimestampOverHigherId() {
        val jobs = listOf(
            testUiJob(id = 50, timestamp = 1_000L, batchId = "batch-oldest", status = "Queued"),
            testUiJob(id = 51, timestamp = 1_000L, batchId = "batch-oldest", status = "Completed"),
            testUiJob(id = 10, timestamp = 2_000L, batchId = "batch-newer", status = "Queued"),
            testUiJob(id = 11, timestamp = 2_000L, batchId = "batch-newer", status = "Completed"),
        )

        assertEquals(
            BatchProgressState(currentFileNumber = 2, totalFiles = 2, completedFiles = 1),
            buildBatchProgressState(jobs),
        )
    }

    @Test
    fun shouldShowRetranscribeAction_returnsTrueForClearedJobWithoutTranscript() {
        assertEquals(
            true,
            testUiJob(
                id = 1,
                batchId = "",
                status = "Cleared",
                transcript = "",
            ).shouldShowRetranscribeAction(),
        )
    }

    @Test
    fun shouldShowRetranscribeAction_returnsFalseForIdleBlankJob() {
        assertEquals(
            false,
            testUiJob(
                id = 1,
                batchId = "",
                status = "Completed",
                transcript = "",
            ).shouldShowRetranscribeAction(),
        )
    }

    @Test
    fun resolveMicrosoftSpeechErrorMessage_mentionsRegionForMissingEndpoint() {
        assertEquals(
            "Microsoft Speech endpoint not found. Check the configured region.",
            resolveMicrosoftSpeechErrorMessage(404, """{"error":{"message":"Resource not found"}}"""),
        )
    }

    @Test
    fun isMicrosoftSpeechUploadSizeSupported_allowsExactDocumentedLimit() {
        assertEquals(true, isMicrosoftSpeechUploadSizeSupported(70_000_000L))
    }

    @Test
    fun isMicrosoftSpeechUploadSizeSupported_rejectsPayloadsAboveDocumentedLimit() {
        assertEquals(false, isMicrosoftSpeechUploadSizeSupported(70_000_001L))
    }

    @Test
    fun parseMicrosoftSpeechTranscriptText_readsCombinedPhrasesFromJsonPayload() {
        val response = JSONObject(
            """
            {
              "combinedPhrases": [
                { "text": "Hello from MAI" },
                { "text": "Second line" }
              ],
              "phrases": [
                { "text": "Ignored fallback", "locale": "en-US" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("Hello from MAI\nSecond line", parseMicrosoftSpeechTranscriptText(response))
        assertEquals("en-US", parseMicrosoftSpeechLanguage(response))
    }

    @Test
    fun parseMicrosoftSpeechTranscriptText_fallsBackToPhrasesWhenCombinedPhrasesMissing() {
        val response = JSONObject(
            """
            {
              "phrases": [
                { "text": "Ciao mondo", "locale": "it-IT" },
                { "text": "Seconda riga" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("Ciao mondo\nSeconda riga", parseMicrosoftSpeechTranscriptText(response))
        assertEquals("it-IT", parseMicrosoftSpeechLanguage(response))
    }

    @Test
    fun parseTranscriptTextValue_returnsFallbackForBlankPlainTranscript() {
        assertEquals(
            "No text found.",
            parseTranscriptTextValue("   \n\t  ", speakerDiarizationEnabled = false),
        )
    }

    @Test
    fun parseTranscriptTextValue_returnsFallbackForBlankDiarizedTranscript() {
        assertEquals(
            "No text found.",
            parseTranscriptTextValue("  \n   \n", speakerDiarizationEnabled = true),
        )
    }

    @Test
    fun parseTranscriptTextValue_trimsAndPreservesNonBlankDiarizedLines() {
        assertEquals(
            "Speaker A: Hello\nSpeaker B: Hi",
            parseTranscriptTextValue(
                "  Speaker A: Hello  \n\n   Speaker B: Hi   \n",
                speakerDiarizationEnabled = true,
            ),
        )
    }

    private fun testUiJob(
        id: Int,
        timestamp: Long = id.toLong(),
        batchId: String,
        status: String,
        isRunning: Boolean = false,
        transcript: String = "Transcript",
    ) = TranscriptionJobState(
        id = id,
        timestamp = timestamp,
        displayName = "file-$id.wav",
        batchId = batchId,
        status = status,
        isRunning = isRunning,
        transcript = transcript,
    )
}
