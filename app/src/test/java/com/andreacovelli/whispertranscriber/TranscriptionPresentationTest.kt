package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.background.PostTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.queue.JobExecutionBackend
import com.andreacovelli.whispertranscriber.queue.postTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.services.resolveMicrosoftSpeechKeyValidationResult
import com.andreacovelli.whispertranscriber.services.resolveMicrosoftSpeechKeyValidationResults
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.decodeHistoricalSummarizerProvider
import com.andreacovelli.whispertranscriber.settings.decodeTranscriptionProvider
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CLEARED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_COMPLETED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_SUMMARIZING
import com.andreacovelli.whispertranscriber.transcription.displayJobStatus
import com.andreacovelli.whispertranscriber.transcription.displaySummaryStatus
import com.andreacovelli.whispertranscriber.transcription.hasSpeakerLabels
import com.andreacovelli.whispertranscriber.transcription.isMicrosoftSpeechUploadSizeSupported
import com.andreacovelli.whispertranscriber.transcription.parseMicrosoftSpeechDiarizedTranscriptText
import com.andreacovelli.whispertranscriber.transcription.parseMicrosoftSpeechLanguage
import com.andreacovelli.whispertranscriber.transcription.parseMicrosoftSpeechTranscriptText
import com.andreacovelli.whispertranscriber.transcription.parseTranscriptTextValue
import com.andreacovelli.whispertranscriber.transcription.resolveMicrosoftSpeechErrorMessage
import com.andreacovelli.whispertranscriber.ui.components.configuredCustomModelLabel
import com.andreacovelli.whispertranscriber.ui.components.providerFamilyLabel
import com.andreacovelli.whispertranscriber.ui.components.providerFamilyLabelForModel
import com.andreacovelli.whispertranscriber.ui.components.shouldShowStopAction
import com.andreacovelli.whispertranscriber.ui.main.TranscriptionJobState
import com.andreacovelli.whispertranscriber.ui.screens.BatchProgressState
import com.andreacovelli.whispertranscriber.ui.screens.buildBatchProgressState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionPresentationTest {
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
    fun decodeTranscriptionProvider_mapsLegacyGemini25FlashValue() {
        assertEquals(
            TranscriptionProvider.GEMINI_FLASH,
            decodeTranscriptionProvider("GEMINI_25_FLASH"),
        )
    }

    @Test
    fun decodeTranscriptionProvider_mapsUnknownValueToMicrosoft() {
        assertEquals(
            TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
            decodeTranscriptionProvider("REMOVED_PROVIDER"),
        )
    }

    @Test
    fun decodeTranscriptionProvider_mapsMissingValueToMicrosoft() {
        assertEquals(
            TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
            decodeTranscriptionProvider(null),
        )
    }

    @Test
    fun decodeHistoricalSummarizerProvider_returnsNullForNonSummarizerProviders() {
        assertNull(decodeHistoricalSummarizerProvider("REMOVED_PROVIDER"))
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
        assertEquals(R.string.provider_label_gemini_custom, providerFamilyLabel(TranscriptionProvider.GEMINI_CUSTOM))
    }

    @Test
    fun providerFamilyLabel_returnsMicrosoftLabelForMicrosoftProvider() {
        assertEquals(
            R.string.provider_label_microsoft,
            providerFamilyLabel(TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE),
        )
    }

    @Test
    fun providerFamilyLabelForModel_returnsKnownProviderLabelForBuiltInModel() {
        assertEquals(R.string.provider_option_flash, providerFamilyLabelForModel("gemini-flash-latest"))
    }

    @Test
    fun providerFamilyLabelForModel_classifiesVersionedFlashModelsAsGeminiFlash() {
        assertEquals(R.string.provider_option_flash, providerFamilyLabelForModel("gemini-2.5-flash-preview-04-17"))
    }

    @Test
    fun providerFamilyLabelForModel_classifiesVersionedFlashLiteModelsAsGeminiFlashLite() {
        assertEquals(
            R.string.provider_option_flash_lite,
            providerFamilyLabelForModel("gemini-2.0-flash-lite-preview"),
        )
    }

    @Test
    fun providerFamilyLabelForModel_returnsGeminiCustomForUnknownModel() {
        assertEquals(R.string.provider_label_gemini_custom, providerFamilyLabelForModel("gemini-experimental-foo"))
    }

    @Test
    fun providerFamilyLabel_prefersExplicitCustomSummaryProviderOverModelHeuristics() {
        assertEquals(
            R.string.provider_label_gemini_custom,
            providerFamilyLabel(TranscriptionProvider.GEMINI_CUSTOM, "gemini-2.0-flash-lite-preview"),
        )
    }

    @Test
    fun configuredCustomModelLabel_includesConfiguredCustomModelName() {
        assertEquals(
            "gemini-2.5-flash",
            configuredCustomModelLabel(TranscriptionProvider.GEMINI_CUSTOM, "gemini-2.5-flash"),
        )
    }

    @Test
    fun configuredCustomModelLabel_isNullForBlankCustomModelNameOrBuiltInProvider() {
        assertNull(configuredCustomModelLabel(TranscriptionProvider.GEMINI_CUSTOM, " "))
        assertNull(configuredCustomModelLabel(TranscriptionProvider.GEMINI_FLASH, "gemini-2.5-flash"))
    }

    @Test
    fun buildBatchProgressState_returnsNullForSingleJob() {
        val jobs = listOf(
            testUiJob(id = 1, batchId = "batch-a", status = JOB_STATUS_QUEUED),
        )

        assertNull(buildBatchProgressState(jobs))
    }

    @Test
    fun buildBatchProgressState_usesLatestActiveMultiFileBatch() {
        val jobs = listOf(
            testUiJob(id = 1, timestamp = 1_000L, batchId = "batch-old", status = JOB_STATUS_COMPLETED),
            testUiJob(id = 2, timestamp = 2_000L, batchId = "batch-old", status = JOB_STATUS_COMPLETED),
            testUiJob(id = 3, timestamp = 3_000L, batchId = "batch-new", status = JOB_STATUS_COMPLETED),
            testUiJob(id = 4, timestamp = 4_000L, batchId = "batch-new", status = "Transcribing", isRunning = true),
            testUiJob(id = 5, timestamp = 5_000L, batchId = "batch-new", status = JOB_STATUS_QUEUED),
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
            testUiJob(id = 51, timestamp = 1_000L, batchId = "batch-running", status = JOB_STATUS_COMPLETED),
            testUiJob(id = 10, timestamp = 2_000L, batchId = "batch-newer", status = JOB_STATUS_QUEUED),
            testUiJob(id = 11, timestamp = 2_000L, batchId = "batch-newer", status = JOB_STATUS_QUEUED),
        )

        assertEquals(
            BatchProgressState(currentFileNumber = 2, totalFiles = 2, completedFiles = 1),
            buildBatchProgressState(jobs),
        )
    }

    @Test
    fun buildBatchProgressState_prefersEarliestQueuedTimestampOverHigherId() {
        val jobs = listOf(
            testUiJob(id = 50, timestamp = 1_000L, batchId = "batch-oldest", status = JOB_STATUS_QUEUED),
            testUiJob(id = 51, timestamp = 1_000L, batchId = "batch-oldest", status = JOB_STATUS_COMPLETED),
            testUiJob(id = 10, timestamp = 2_000L, batchId = "batch-newer", status = JOB_STATUS_QUEUED),
            testUiJob(id = 11, timestamp = 2_000L, batchId = "batch-newer", status = JOB_STATUS_COMPLETED),
        )

        assertEquals(
            BatchProgressState(currentFileNumber = 2, totalFiles = 2, completedFiles = 1),
            buildBatchProgressState(jobs),
        )
    }

    @Test
    fun shouldShowStopAction_returnsFalseForClearedJobWithoutTranscript() {
        assertEquals(
            false,
            testUiJob(
                id = 1,
                batchId = "",
                status = JOB_STATUS_CLEARED,
                transcript = "",
            ).shouldShowStopAction(),
        )
    }

    @Test
    fun shouldShowStopAction_returnsFalseForIdleBlankJob() {
        assertEquals(
            false,
            testUiJob(
                id = 1,
                batchId = "",
                status = JOB_STATUS_COMPLETED,
                transcript = "",
            ).shouldShowStopAction(),
        )
    }

    @Test
    fun displayStatusHelpers_mapInternalStatusesToDisplayLabels() {
        assertEquals(R.string.job_status_cleared, displayJobStatus(JOB_STATUS_CLEARED))
        assertEquals(R.string.job_status_error, displayJobStatus(JOB_STATUS_ERROR))
        assertEquals(R.string.job_status_cancelled, displayJobStatus(JOB_STATUS_CANCELLED))
        assertEquals(R.string.summary_status_summarizing, displaySummaryStatus(SUMMARY_STATUS_SUMMARIZING))
        assertNull(displayJobStatus("Transcribing audio"))
    }

    @Test
    fun resolveMicrosoftSpeechErrorMessage_mentionsConfigurationForMissingEndpoint() {
        assertEquals(
            UserMessage(R.string.microsoft_speech_endpoint_not_found),
            resolveMicrosoftSpeechErrorMessage(404, """{"error":{"message":"Resource not found"}}"""),
        )
    }

    @Test
    fun resolveMicrosoftSpeechErrorMessage_mapsDetailedCodeOfTopLevelError() {
        assertEquals(
            UserMessage(R.string.microsoft_speech_audio_format_unsupported),
            resolveMicrosoftSpeechErrorMessage(
                400,
                """{"code":"InvalidRequest","message":"Audio format not supported.",""" +
                    """"innerError":{"code":"InvalidAudioFormat","message":"Unsupported format."}}""",
            ),
        )
    }

    @Test
    fun resolveMicrosoftSpeechErrorMessage_mapsCodeOfWrappedError() {
        assertEquals(
            UserMessage(R.string.microsoft_speech_access_denied),
            resolveMicrosoftSpeechErrorMessage(403, """{"error":{"code":"Forbidden","message":"Access denied."}}"""),
        )
    }

    @Test
    fun resolveMicrosoftSpeechErrorMessage_prefersMostDetailedKnownCode() {
        assertEquals(
            UserMessage(R.string.microsoft_speech_language_not_identified),
            resolveMicrosoftSpeechErrorMessage(
                400,
                """{"code":"UnsupportedMediaType","innerError":{"code":"InvalidPayload",""" +
                    """"innerError":{"code":"NoLanguageIdentified"}}}""",
            ),
        )
    }

    @Test
    fun resolveMicrosoftSpeechErrorMessage_keepsGenericMessageForUnmappedCode() {
        assertEquals(
            UserMessage(
                R.string.http_error_unexpected,
                listOf(400, UserMessage(R.string.provider_name_microsoft_speech)),
            ),
            resolveMicrosoftSpeechErrorMessage(400, """{"code":"InvalidArgument","message":"Invalid definition."}"""),
        )
    }

    @Test
    fun resolveMicrosoftSpeechErrorMessage_keepsRetryableMessagesForRetriedStatuses() {
        assertEquals(
            UserMessage(R.string.http_error_rate_limited),
            resolveMicrosoftSpeechErrorMessage(429, """{"code":"TooManyRequests","message":"Slow down."}"""),
        )
        assertEquals(
            UserMessage(R.string.microsoft_speech_resource_exhausted),
            resolveMicrosoftSpeechErrorMessage(429, """{"code":"QuotaViolation","message":"Quota exceeded."}"""),
        )
        assertEquals(
            UserMessage(R.string.http_error_server, listOf(503, UserMessage(R.string.provider_name_microsoft_speech))),
            resolveMicrosoftSpeechErrorMessage(503, """{"code":"InvalidAudioFormat"}"""),
        )
    }

    @Test
    fun resolveMicrosoftSpeechErrorMessage_keepsGenericMessageWithoutServiceReason() {
        assertEquals(
            UserMessage(
                R.string.http_error_unexpected,
                listOf(400, UserMessage(R.string.provider_name_microsoft_speech)),
            ),
            resolveMicrosoftSpeechErrorMessage(400, "Bad Request"),
        )
    }

    @Test
    fun isMicrosoftSpeechUploadSizeSupported_allowsPayloadJustBelowDocumentedLimit() {
        assertEquals(true, isMicrosoftSpeechUploadSizeSupported(249_999_999L))
    }

    @Test
    fun isMicrosoftSpeechUploadSizeSupported_rejectsPayloadAtDocumentedLimit() {
        assertEquals(false, isMicrosoftSpeechUploadSizeSupported(250_000_000L))
    }

    @Test
    fun isMicrosoftSpeechUploadSizeSupported_rejectsPayloadsAboveDocumentedLimit() {
        assertEquals(false, isMicrosoftSpeechUploadSizeSupported(250_000_001L))
    }

    @Test
    fun resolveMicrosoftSpeechKeyValidationResult_acceptsSuccessfulLocalesProbe() {
        assertEquals(true, resolveMicrosoftSpeechKeyValidationResult(200).isValid)
    }

    @Test
    fun resolveMicrosoftSpeechKeyValidationResult_rejectsBadRequestProbe() {
        assertEquals(
            UserMessage(R.string.validation_failed_http, listOf(400)),
            resolveMicrosoftSpeechKeyValidationResult(400).errorMessage,
        )
    }

    @Test
    fun resolveMicrosoftSpeechKeyValidationResult_rejectsUnauthorizedKey() {
        assertEquals(
            UserMessage(R.string.microsoft_speech_key_invalid),
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
            UserMessage(R.string.validation_failed_http, listOf(404)),
            resolveMicrosoftSpeechKeyValidationResults(listOf(404, 405, 400)).errorMessage,
        )
    }

    @Test
    fun resolveMicrosoftSpeechKeyValidationResult_rejectsUnauthorizedAcrossFallbacks() {
        assertEquals(
            UserMessage(R.string.microsoft_speech_key_invalid),
            resolveMicrosoftSpeechKeyValidationResults(listOf(404, 401, 405)).errorMessage,
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
    fun parseMicrosoftSpeechDiarizedTranscriptText_mergesConsecutivePhrasesAndLabelsByFirstAppearance() {
        val response = JSONObject(
            """
            {
              "combinedPhrases": [{ "text": "Good afternoon. This is Sam. Hi there. Thanks." }],
              "phrases": [
                { "speaker": 1, "text": "Good afternoon." },
                { "speaker": 1, "text": "This is Sam." },
                { "speaker": 0, "text": "Hi there." },
                { "speaker": 1, "text": "Thanks." }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            "Speaker A: Good afternoon. This is Sam.\nSpeaker B: Hi there.\nSpeaker A: Thanks.",
            parseMicrosoftSpeechDiarizedTranscriptText(response, speakerLabelPrefix = "Speaker"),
        )
    }

    @Test
    fun parseMicrosoftSpeechDiarizedTranscriptText_keepsUnattributedPhrasesUnlabeled() {
        val response = JSONObject(
            """
            {
              "phrases": [
                { "speaker": 0, "text": "Hello." },
                { "text": "Unknown speaker." },
                { "speaker": 0, "text": "Bye." }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            "Speaker A: Hello.\nUnknown speaker.\nSpeaker A: Bye.",
            parseMicrosoftSpeechDiarizedTranscriptText(response, speakerLabelPrefix = "  "),
        )
    }

    @Test
    fun parseMicrosoftSpeechDiarizedTranscriptText_fallsBackToPlainTextWithoutSpeakers() {
        val response = JSONObject(
            """
            {
              "combinedPhrases": [{ "text": "Hello from MAI" }],
              "phrases": [{ "text": "Hello from MAI", "locale": "en-US" }]
            }
            """.trimIndent(),
        )

        val text = parseMicrosoftSpeechDiarizedTranscriptText(response, "Speaker")

        assertEquals("Hello from MAI", text)
        assertFalse(hasSpeakerLabels(text, "Speaker"))
    }

    @Test
    fun hasSpeakerLabels_detectsLabeledLinesWithTheConfiguredPrefix() {
        assertTrue(hasSpeakerLabels("Intro\nSpeaker A: Hello", "Speaker"))
        assertTrue(hasSpeakerLabels("Speaker 2: Hello", " "))
        assertTrue(hasSpeakerLabels("Relatore (1) A: Ciao", "Relatore (1)"))
        assertFalse(hasSpeakerLabels("The Speaker A: said hello", "Speaker"))
        assertFalse(hasSpeakerLabels("Speaker A: Hello", "Relatore"))
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
    fun parseTranscriptTextValue_returnsEmptyTextForBlankPlainTranscript() {
        assertEquals(
            "",
            parseTranscriptTextValue("   \n\t  ", speakerDiarizationEnabled = false),
        )
    }

    @Test
    fun parseTranscriptTextValue_returnsEmptyTextForBlankDiarizedTranscript() {
        assertEquals(
            "",
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
