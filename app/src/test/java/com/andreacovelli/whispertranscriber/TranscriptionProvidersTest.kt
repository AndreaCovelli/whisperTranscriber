package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.transcription.AudioInspection
import com.andreacovelli.whispertranscriber.transcription.MICROSOFT_MAI_TRANSCRIBE_MODEL
import com.andreacovelli.whispertranscriber.transcription.MICROSOFT_SPEECH_API_VERSION
import com.andreacovelli.whispertranscriber.transcription.MicrosoftSpeechEndpoint
import com.andreacovelli.whispertranscriber.transcription.MicrosoftSpeechTranscriber
import com.andreacovelli.whispertranscriber.transcription.TranscriptionResult
import com.andreacovelli.whispertranscriber.transcription.buildGeminiSpeakerDiarizationPrompt
import com.andreacovelli.whispertranscriber.transcription.resolveMicrosoftSpeechExecutionModel
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
        val (requestBody, results) = runMicrosoftTranscriber(
            responseJson = """{"combinedPhrases":[{"text":"hello"}]}""",
            modelName = "mai-transcribe-1.5",
            speakerDiarizationEnabled = false,
        )

        assertTrue(requestBody.contains("\"model\":\"mai-transcribe-1.5\""))
        assertFalse(requestBody.contains("diarization"))
        val success = results.filterIsInstance<TranscriptionResult.Success>().single()
        assertEquals("mai-transcribe-1.5", success.data.model)
        assertEquals("hello", success.data.text)
    }

    @Test
    fun microsoftTranscriber_withDiarization_requestsAndLabelsSpeakers() = runBlocking {
        val (requestBody, results) = runMicrosoftTranscriber(
            responseJson = """
                {
                  "combinedPhrases":[{"text":"Hello. Hi."}],
                  "phrases":[
                    {"speaker":0,"text":"Hello.","locale":"en-US"},
                    {"speaker":1,"text":"Hi.","locale":"en-US"}
                  ]
                }
            """.trimIndent(),
            modelName = MICROSOFT_MAI_TRANSCRIBE_MODEL,
            speakerDiarizationEnabled = true,
        )

        assertTrue(requestBody.contains("\"diarization\":{\"enabled\":true}"))
        val success = results.filterIsInstance<TranscriptionResult.Success>().single()
        assertEquals("Locutore A: Hello.\nLocutore B: Hi.", success.data.text)
    }

    private suspend fun runMicrosoftTranscriber(
        responseJson: String,
        modelName: String,
        speakerDiarizationEnabled: Boolean,
    ): Pair<String, List<TranscriptionResult>> {
        var requestBody = ""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestBody = Buffer().use { buffer ->
                    checkNotNull(chain.request().body).writeTo(buffer)
                    buffer.readUtf8()
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody("application/json".toMediaType()))
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
                audioInspection = AudioInspection(sizeBytes = 1, durationUs = 1),
                okHttpClient = client,
                ioDispatcher = Dispatchers.Unconfined,
            ).transcribe().toList()
            requestBody to results
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
