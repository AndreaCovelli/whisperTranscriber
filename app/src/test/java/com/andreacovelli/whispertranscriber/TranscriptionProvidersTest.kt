package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.transcription.AudioInspection
import com.andreacovelli.whispertranscriber.transcription.MICROSOFT_DIARIZATION_FALLBACK_MIN_DURATION_US
import com.andreacovelli.whispertranscriber.transcription.MICROSOFT_MAI_TRANSCRIBE_MODEL
import com.andreacovelli.whispertranscriber.transcription.MICROSOFT_SPEECH_API_VERSION
import com.andreacovelli.whispertranscriber.transcription.MicrosoftSpeechEndpoint
import com.andreacovelli.whispertranscriber.transcription.MicrosoftSpeechTranscriber
import com.andreacovelli.whispertranscriber.transcription.TranscriptionHints
import com.andreacovelli.whispertranscriber.transcription.TranscriptionResult
import com.andreacovelli.whispertranscriber.transcription.TransientWorkException
import com.andreacovelli.whispertranscriber.transcription.buildGeminiSpeakerDiarizationPrompt
import com.andreacovelli.whispertranscriber.transcription.resolveMicrosoftSpeechExecutionModel
import com.andreacovelli.whispertranscriber.transcription.shouldRetryMicrosoftSpeechWithoutDiarization
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionProvidersTest {
    @Test
    fun microsoftMaiPreset_usesMaiTranscribe2() {
        assertEquals("MAI-Transcribe-2", MICROSOFT_MAI_TRANSCRIBE_MODEL)
        assertEquals("2025-10-15", MICROSOFT_SPEECH_API_VERSION)
        assertEquals("MAI-Transcribe-2", TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE.modelName)
    }

    @Test
    fun microsoftExecution_usesModelStoredWithQueuedJob() {
        assertEquals("mai-transcribe-1.5", resolveMicrosoftSpeechExecutionModel("mai-transcribe-1.5"))
    }

    @Test
    fun microsoftExecution_withoutStoredModel_usesCurrentPreset() {
        assertEquals(MICROSOFT_MAI_TRANSCRIBE_MODEL, resolveMicrosoftSpeechExecutionModel(null))
        assertEquals(MICROSOFT_MAI_TRANSCRIBE_MODEL, resolveMicrosoftSpeechExecutionModel(""))
    }

    @Test
    fun microsoftTranscriber_sendsAndReportsStoredModel() = runBlocking {
        val (requestBodies, results) = runMicrosoftTranscriber(
            responses = listOf(200 to """{"combinedPhrases":[{"text":"hello"}]}"""),
            modelName = "mai-transcribe-1.5",
            speakerDiarizationEnabled = false,
        )
        val requestBody = requestBodies.single()

        assertTrue(requestBody.contains("\"model\":\"mai-transcribe-1.5\""))
        assertFalse(requestBody.contains("diarization"))
        val success = results.filterIsInstance<TranscriptionResult.Success>().single()
        assertEquals("mai-transcribe-1.5", success.data.model)
        assertEquals("hello", success.data.text)
    }

    @Test
    fun microsoftTranscriber_withDiarization_requestsAndLabelsSpeakers() = runBlocking {
        val (requestBodies, results) = runMicrosoftTranscriber(
            responses = listOf(
                200 to """
                {
                  "combinedPhrases":[{"text":"Hello. Hi."}],
                  "phrases":[
                    {"speaker":0,"text":"Hello.","locale":"en-US"},
                    {"speaker":1,"text":"Hi.","locale":"en-US"}
                  ]
                }
                """.trimIndent(),
            ),
            modelName = MICROSOFT_MAI_TRANSCRIBE_MODEL,
            speakerDiarizationEnabled = true,
        )

        assertTrue(requestBodies.single().contains("\"diarization\":{\"enabled\":true}"))
        val success = results.filterIsInstance<TranscriptionResult.Success>().single()
        assertEquals("Locutore A: Hello.\nLocutore B: Hi.", success.data.text)
    }

    @Test
    fun microsoftTranscriber_longRecordingDiarizationFailure_retriesWithoutDiarization() = runBlocking {
        val (requestBodies, results) = runMicrosoftTranscriber(
            responses = listOf(
                503 to """{"error":{"code":"diarization_unavailable"}}""",
                200 to """{"combinedPhrases":[{"text":"Hello. Hi."}],"phrases":[{"text":"Hello. Hi."}]}""",
            ),
            modelName = MICROSOFT_MAI_TRANSCRIBE_MODEL,
            speakerDiarizationEnabled = true,
            durationUs = MICROSOFT_DIARIZATION_FALLBACK_MIN_DURATION_US,
        )

        assertEquals(2, requestBodies.size)
        assertTrue(requestBodies[0].contains("\"diarization\":{\"enabled\":true}"))
        assertFalse(requestBodies[1].contains("diarization"))
        assertTrue(
            results.contains(
                TranscriptionResult.Loading(UserMessage(R.string.transcription_status_diarization_fallback)),
            ),
        )
        val success = results.filterIsInstance<TranscriptionResult.Success>().single()
        assertEquals("Hello. Hi.", success.data.text)
    }

    @Test
    fun microsoftTranscriber_shortRecordingDiarizationFailure_reportsRetryableError() = runBlocking {
        val (requestBodies, results) = runMicrosoftTranscriber(
            responses = listOf(503 to """{"error":{"code":"diarization_unavailable"}}"""),
            modelName = MICROSOFT_MAI_TRANSCRIBE_MODEL,
            speakerDiarizationEnabled = true,
            durationUs = MICROSOFT_DIARIZATION_FALLBACK_MIN_DURATION_US - 1,
        )

        assertEquals(1, requestBodies.size)
        val error = results.filterIsInstance<TranscriptionResult.Error>().single()
        assertTrue(error.exception is TransientWorkException)
    }

    @Test
    fun shouldRetryMicrosoftSpeechWithoutDiarization_onlyForLongRecordingsAndReportedCodes() {
        val long = MICROSOFT_DIARIZATION_FALLBACK_MIN_DURATION_US
        assertTrue(shouldRetryMicrosoftSpeechWithoutDiarization(408, long))
        assertTrue(shouldRetryMicrosoftSpeechWithoutDiarization(500, long))
        assertTrue(shouldRetryMicrosoftSpeechWithoutDiarization(503, long))
        assertFalse(shouldRetryMicrosoftSpeechWithoutDiarization(429, long))
        assertFalse(shouldRetryMicrosoftSpeechWithoutDiarization(401, long))
        assertFalse(shouldRetryMicrosoftSpeechWithoutDiarization(503, long - 1))
    }

    private suspend fun runMicrosoftTranscriber(
        responses: List<Pair<Int, String>>,
        modelName: String,
        speakerDiarizationEnabled: Boolean,
        durationUs: Long = 1,
    ): Pair<List<String>, List<TranscriptionResult>> {
        val requestBodies = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestBodies += Buffer().use { buffer ->
                    checkNotNull(chain.request().body).writeTo(buffer)
                    buffer.readUtf8()
                }
                val (code, body) = responses[requestBodies.size - 1]
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("HTTP $code")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val audioFile = File.createTempFile("microsoft-model", ".wav").apply {
            writeBytes(byteArrayOf(1))
        }

        return try {
            val results = MicrosoftSpeechTranscriber(
                apiKey = "test-key".toCharArray(),
                audioFile = audioFile,
                mimeType = "audio/wav",
                endpoint = MicrosoftSpeechEndpoint.parse("swedencentral"),
                modelName = modelName,
                speakerDiarizationEnabled = speakerDiarizationEnabled,
                speakerLabelPrefix = "Locutore",
                hints = TranscriptionHints(),
                audioInspection = AudioInspection(sizeBytes = 1, durationUs = durationUs),
                okHttpClient = client,
                ioDispatcher = Dispatchers.Unconfined,
            ).transcribe().toList()
            requestBodies to results
        } finally {
            audioFile.delete()
        }
    }

    @Test
    fun buildGeminiSpeakerDiarizationPrompt_usesLocalizedSpeakerLabelPrefix() {
        val prompt = buildGeminiSpeakerDiarizationPrompt("Locutore")

        assertTrue(prompt.contains("Locutore A: Hello"))
        assertTrue(prompt.contains("Locutore B: Hi"))
    }

    @Test
    fun buildGeminiSpeakerDiarizationPrompt_fallsBackToSpeakerWhenPrefixBlank() {
        val prompt = buildGeminiSpeakerDiarizationPrompt("   ")

        assertTrue(prompt.contains("Speaker A: Hello"))
        assertTrue(prompt.contains("Speaker B: Hi"))
    }
}
