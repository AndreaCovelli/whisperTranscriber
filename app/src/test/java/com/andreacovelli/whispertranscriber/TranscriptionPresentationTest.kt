package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.background.PostTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.queue.JobExecutionBackend
import com.andreacovelli.whispertranscriber.queue.postTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.services.resolveMicrosoftSpeechKeyValidationResult
import com.andreacovelli.whispertranscriber.services.resolveMicrosoftSpeechKeyValidationResults
import com.andreacovelli.whispertranscriber.services.resolveMicrosoftSpeechRegionIOExceptionMessage
import com.andreacovelli.whispertranscriber.services.resolveMicrosoftSpeechRegionValidationResults
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.TranscriptionQueueSettings
import com.andreacovelli.whispertranscriber.settings.decodeHistoricalSummarizerProvider
import com.andreacovelli.whispertranscriber.settings.decodeHistoricalTranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.decodeStoredTranscriptionProvider
import com.andreacovelli.whispertranscriber.transcription.DISPLAY_JOB_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.DISPLAY_JOB_STATUS_CLEARED
import com.andreacovelli.whispertranscriber.transcription.DISPLAY_JOB_STATUS_COMPLETED
import com.andreacovelli.whispertranscriber.transcription.DISPLAY_JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.DISPLAY_JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.DISPLAY_SUMMARY_STATUS_SUMMARIZING
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CLEARED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_SUMMARIZING
import com.andreacovelli.whispertranscriber.transcription.displayJobStatus
import com.andreacovelli.whispertranscriber.transcription.displaySummaryStatus
import com.andreacovelli.whispertranscriber.transcription.isMicrosoftSpeechUploadSizeSupported
import com.andreacovelli.whispertranscriber.transcription.parseMicrosoftSpeechLanguage
import com.andreacovelli.whispertranscriber.transcription.parseMicrosoftSpeechTranscriptText
import com.andreacovelli.whispertranscriber.transcription.parseTranscriptTextValue
import com.andreacovelli.whispertranscriber.transcription.resolveMicrosoftSpeechErrorMessage
import com.andreacovelli.whispertranscriber.ui.components.providerDisplayLabel
import com.andreacovelli.whispertranscriber.ui.components.providerFamilyLabel
import com.andreacovelli.whispertranscriber.ui.components.providerFamilyLabelForModel
import com.andreacovelli.whispertranscriber.ui.components.shouldShowRetranscribeAction
import com.andreacovelli.whispertranscriber.ui.main.TranscriptionJobState
import com.andreacovelli.whispertranscriber.ui.main.UiState
import com.andreacovelli.whispertranscriber.ui.main.resolveShareQueueSettings
import com.andreacovelli.whispertranscriber.ui.main.toAutomaticSummarizationWorkSpec
import com.andreacovelli.whispertranscriber.ui.main.toSummarizationSettings
import com.andreacovelli.whispertranscriber.ui.main.toTranscriptionQueueSettings
import com.andreacovelli.whispertranscriber.ui.screens.BatchProgressState
import com.andreacovelli.whispertranscriber.ui.screens.buildBatchProgressState
import java.io.IOException
import java.net.UnknownHostException
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
    fun postTranscriptionWorkSpec_roundTripsThroughQueuedJobPersistenceHelpers() {
        val spec = PostTranscriptionWorkSpec(
            summarizeAfterTranscription = true,
            notifyResult = true,
            summarizationSettings = SummarizationSettings(
                provider = TranscriptionProvider.GEMINI_CUSTOM,
                customModelName = "gemini-custom-summary",
            ),
        )

        val restored = TranscriptionJob(
            uri = "content://test/audio.wav",
            displayName = "audio.wav",
            executionBackend = JobExecutionBackend.USER_INITIATED_JOB.name,
            summarizeAfterTranscription = spec.summarizeAfterTranscription,
            notifyResult = spec.notifyResult,
            queuedSummarizerProviderName = spec.summarizationSettings.provider.name,
            queuedSummarizerCustomModelName = spec.summarizationSettings.customModelName,
        ).postTranscriptionWorkSpec()

        assertEquals(spec, restored)
    }

    @Test
    fun postTranscriptionWorkSpec_returnsNullWhenQueuedJobHasNoFollowUpWork() {
        val restored = TranscriptionJob(
            uri = "content://test/audio.wav",
            displayName = "audio.wav",
            summarizeAfterTranscription = false,
            notifyResult = false,
        )

        assertNull(restored.postTranscriptionWorkSpec())
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
    fun decodeHistoricalSummarizerProvider_returnsNullForLegacyTranscriptionOnlyProviders() {
        assertNull(decodeHistoricalSummarizerProvider("ELEVEN_LABS"))
        assertNull(decodeHistoricalSummarizerProvider("MICROSOFT_MAI_TRANSCRIBE"))
    }

    @Test
    fun decodeHistoricalSummarizerProvider_mapsLegacyGeminiNamesAndRejectsUnknowns() {
        assertEquals(
            TranscriptionProvider.GEMINI_FLASH,
            decodeHistoricalSummarizerProvider("GEMINI_25_FLASH"),
        )
        assertNull(decodeHistoricalSummarizerProvider("NOT_A_PROVIDER"))
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
            testUiJob(id = 1, batchId = "batch-a", status = DISPLAY_JOB_STATUS_QUEUED),
        )

        assertNull(buildBatchProgressState(jobs))
    }

    @Test
    fun buildBatchProgressState_usesLatestActiveMultiFileBatch() {
        val jobs = listOf(
            testUiJob(id = 1, timestamp = 1_000L, batchId = "batch-old", status = DISPLAY_JOB_STATUS_COMPLETED),
            testUiJob(id = 2, timestamp = 2_000L, batchId = "batch-old", status = DISPLAY_JOB_STATUS_COMPLETED),
            testUiJob(id = 3, timestamp = 3_000L, batchId = "batch-new", status = DISPLAY_JOB_STATUS_COMPLETED),
            testUiJob(id = 4, timestamp = 4_000L, batchId = "batch-new", status = "Transcribing", isRunning = true),
            testUiJob(id = 5, timestamp = 5_000L, batchId = "batch-new", status = DISPLAY_JOB_STATUS_QUEUED),
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
            testUiJob(id = 51, timestamp = 1_000L, batchId = "batch-running", status = DISPLAY_JOB_STATUS_COMPLETED),
            testUiJob(id = 10, timestamp = 2_000L, batchId = "batch-newer", status = DISPLAY_JOB_STATUS_QUEUED),
            testUiJob(id = 11, timestamp = 2_000L, batchId = "batch-newer", status = DISPLAY_JOB_STATUS_QUEUED),
        )

        assertEquals(
            BatchProgressState(currentFileNumber = 2, totalFiles = 2, completedFiles = 1),
            buildBatchProgressState(jobs),
        )
    }

    @Test
    fun buildBatchProgressState_prefersEarliestQueuedTimestampOverHigherId() {
        val jobs = listOf(
            testUiJob(id = 50, timestamp = 1_000L, batchId = "batch-oldest", status = DISPLAY_JOB_STATUS_QUEUED),
            testUiJob(id = 51, timestamp = 1_000L, batchId = "batch-oldest", status = DISPLAY_JOB_STATUS_COMPLETED),
            testUiJob(id = 10, timestamp = 2_000L, batchId = "batch-newer", status = DISPLAY_JOB_STATUS_QUEUED),
            testUiJob(id = 11, timestamp = 2_000L, batchId = "batch-newer", status = DISPLAY_JOB_STATUS_COMPLETED),
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
                status = DISPLAY_JOB_STATUS_CLEARED,
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
                status = DISPLAY_JOB_STATUS_COMPLETED,
                transcript = "",
            ).shouldShowRetranscribeAction(),
        )
    }

    @Test
    fun displayStatusHelpers_mapInternalStatusesToDisplayLabels() {
        assertEquals(DISPLAY_JOB_STATUS_CLEARED, displayJobStatus(JOB_STATUS_CLEARED))
        assertEquals(DISPLAY_JOB_STATUS_ERROR, displayJobStatus(JOB_STATUS_ERROR))
        assertEquals(DISPLAY_JOB_STATUS_CANCELLED, displayJobStatus(JOB_STATUS_CANCELLED))
        assertEquals(DISPLAY_SUMMARY_STATUS_SUMMARIZING, displaySummaryStatus(SUMMARY_STATUS_SUMMARIZING))
    }

    @Test
    fun resolveMicrosoftSpeechErrorMessage_mentionsRegionForMissingEndpoint() {
        assertEquals(
            "Microsoft MAI Transcribe endpoint not found. Check the configured Speech region.",
            resolveMicrosoftSpeechErrorMessage(404, """{"error":{"message":"Resource not found"}}"""),
        )
    }

    @Test
    fun isMicrosoftSpeechUploadSizeSupported_allowsPayloadJustBelowDocumentedLimit() {
        assertEquals(true, isMicrosoftSpeechUploadSizeSupported(69_999_999L))
    }

    @Test
    fun isMicrosoftSpeechUploadSizeSupported_rejectsPayloadAtDocumentedLimit() {
        assertEquals(false, isMicrosoftSpeechUploadSizeSupported(70_000_000L))
    }

    @Test
    fun isMicrosoftSpeechUploadSizeSupported_rejectsPayloadsAboveDocumentedLimit() {
        assertEquals(false, isMicrosoftSpeechUploadSizeSupported(70_000_001L))
    }

    @Test
    fun resolveMicrosoftSpeechKeyValidationResult_acceptsSuccessfulLocalesProbe() {
        assertEquals(true, resolveMicrosoftSpeechKeyValidationResult(200).isValid)
    }

    @Test
    fun resolveMicrosoftSpeechKeyValidationResult_rejectsBadRequestProbe() {
        assertEquals("Validation failed: HTTP 400", resolveMicrosoftSpeechKeyValidationResult(400).errorMessage)
    }

    @Test
    fun resolveMicrosoftSpeechKeyValidationResult_rejectsUnauthorizedKey() {
        assertEquals(
            "Invalid key for Microsoft MAI Transcribe. Use the Speech resource key from your Foundry resource, not a project API key.",
            resolveMicrosoftSpeechKeyValidationResult(401).errorMessage,
        )
    }

    @Test
    fun resolveMicrosoftSpeechKeyValidationResult_acceptsSuccessfulProbeAmongFallbacks() {
        assertEquals(true, resolveMicrosoftSpeechKeyValidationResults(listOf(404, 200, 404)).isValid)
    }

    @Test
    fun resolveMicrosoftSpeechKeyValidationResult_rejectsInconclusiveProbeResponses() {
        assertEquals(
            "Validation failed: HTTP 404",
            resolveMicrosoftSpeechKeyValidationResults(listOf(404, 405, 400)).errorMessage,
        )
    }

    @Test
    fun resolveMicrosoftSpeechKeyValidationResult_rejectsUnauthorizedAcrossFallbacks() {
        assertEquals(
            "Invalid key for Microsoft MAI Transcribe. Use the Speech resource key from your Foundry resource, not a project API key.",
            resolveMicrosoftSpeechKeyValidationResults(listOf(404, 401, 405)).errorMessage,
        )
    }

    @Test
    fun resolveMicrosoftSpeechRegionValidationResults_acceptsUnauthorizedProbeAsValidRegion() {
        assertEquals(
            "westus",
            resolveMicrosoftSpeechRegionValidationResults(
                codes = listOf(404, 401, 404),
                normalizedRegion = "westus",
            ).normalizedRegion,
        )
    }

    @Test
    fun resolveMicrosoftSpeechRegionValidationResults_rejectsMissingRegionAcrossFallbacks() {
        assertEquals(
            "Region \"not-a-region\" not found",
            resolveMicrosoftSpeechRegionValidationResults(
                codes = listOf(404, 404, 404),
                normalizedRegion = "not-a-region",
            ).errorMessage,
        )
    }

    @Test
    fun resolveMicrosoftSpeechRegionValidationResults_rejectsInconclusiveServerFailure() {
        assertEquals(
            "Could not verify region: HTTP 503",
            resolveMicrosoftSpeechRegionValidationResults(
                codes = listOf(503, 500, 429),
                normalizedRegion = "westus",
            ).errorMessage,
        )
    }

    @Test
    fun resolveMicrosoftSpeechRegionIOExceptionMessage_rewritesUnknownHostError() {
        assertEquals(
            "Could not reach Azure Speech for region \"not-a-region\". Check that the region name is correct, for example eastus or westus.",
            resolveMicrosoftSpeechRegionIOExceptionMessage(
                normalizedRegion = "not-a-region",
                error = UnknownHostException("Unable to resolve host \"not-a-region.api.cognitive.microsoft.com\""),
            ),
        )
    }

    @Test
    fun resolveMicrosoftSpeechRegionIOExceptionMessage_preservesOtherIoMessages() {
        assertEquals(
            "timeout",
            resolveMicrosoftSpeechRegionIOExceptionMessage(
                normalizedRegion = "westus",
                error = IOException("timeout"),
            ),
        )
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
    fun parseMicrosoftSpeechLanguage_prefersMostFrequentLocaleAcrossPhrases() {
        val response = JSONObject(
            """
            {
              "phrases": [
                { "text": "Hola", "locale": "es-ES" },
                { "text": "Hello", "locale": "en-US" },
                { "text": "World", "locale": "en-US" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("en-US", parseMicrosoftSpeechLanguage(response))
    }

    @Test
    fun parseMicrosoftSpeechLanguage_returnsUndWhenPhrasesMissingOrBlank() {
        val response = JSONObject("""{"phrases":[{"text":"Hello","locale":"  "},{"text":"World"}]}""")

        assertEquals("und", parseMicrosoftSpeechLanguage(response))
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
