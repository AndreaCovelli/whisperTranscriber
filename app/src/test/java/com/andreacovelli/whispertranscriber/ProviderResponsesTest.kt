package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.transcription.ProviderResponseException
import com.andreacovelli.whispertranscriber.transcription.awaitGeminiFileActive
import com.andreacovelli.whispertranscriber.transcription.parseGeminiTranscription
import com.andreacovelli.whispertranscriber.transcription.parseMicrosoftSpeechTranscriptText
import com.andreacovelli.whispertranscriber.transcription.shouldRetryGeminiApiError
import com.andreacovelli.whispertranscriber.transcription.validatedGeminiText
import com.google.genai.errors.ApiException
import com.google.genai.types.File as GenAiFile
import com.google.genai.types.GenerateContentResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderResponsesTest {
    @Test
    fun geminiDuration_rejectsMeasurableAudioAboveTheLimit() {
        val limit = com.andreacovelli.whispertranscriber.transcription.GEMINI_MAX_AUDIO_DURATION_US
        com.andreacovelli.whispertranscriber.transcription.validateGeminiAudioDuration(limit)
        com.andreacovelli.whispertranscriber.transcription.validateGeminiAudioDuration(-1)
        assertThrows(ProviderResponseException::class.java) {
            com.andreacovelli.whispertranscriber.transcription.validateGeminiAudioDuration(limit + 1)
        }
    }

    @Test
    fun gemini_rejectsMissingBlockedTruncatedAndNonTextCandidates() {
        val responses = listOf(
            "{}",
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            candidate("MAX_TOKENS", "private transcript"),
            candidate("SAFETY", "private transcript"),
            """{"candidates":[{"content":{"parts":[{"text":"private transcript"}]}}]}""",
            candidate("STOP", "   "),
            """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[
                {"inlineData":{"data":"YQ==","mimeType":"audio/wav"}}]}}]}
            """.trimIndent(),
        )
        responses.forEach { json ->
            val error = assertThrows(ProviderResponseException::class.java) {
                validatedGeminiText(GenerateContentResponse.fromJson(json))
            }
            assertFalse(error.message.orEmpty().contains("private transcript"))
            assertEquals(null, error.cause)
        }
    }

    @Test
    fun gemini_acceptsCompleteTextAndExplicitNoSpeech() {
        assertEquals("summary", validatedGeminiText(GenerateContentResponse.fromJson(candidate("STOP", "summary"))))
        val noSpeech = parseGeminiTranscription("""{"text":"","language_code":"und"}""", false, "model")
        assertEquals("", noSpeech.text)
        assertEquals("und", noSpeech.language)
    }

    @Test
    fun malformedTranscripts_neverBecomeNoSpeechOrExposePayloads() {
        listOf(
            "private transcript",
            "{}",
            """{"text":12,"language_code":"en"}""",
            """{"text":"private transcript","language_code":false}""",
        )
            .forEach { json ->
                val error =
                    assertThrows(ProviderResponseException::class.java) {
                        parseGeminiTranscription(json, false, "model")
                    }
                assertEquals(R.string.provider_response_invalid, error.messageId)
                assertFalse(error.message.orEmpty().contains("private transcript"))
            }
        listOf("{}", """{"combinedPhrases":false}""", """{"phrases":[{"text":5}]}""").forEach { json ->
            assertThrows(ProviderResponseException::class.java) { parseMicrosoftSpeechTranscriptText(JSONObject(json)) }
        }
        assertEquals("", parseMicrosoftSpeechTranscriptText(JSONObject("""{"phrases":[]}""")))
    }

    @Test
    fun fileReadiness_pollsUntilActiveAndRejectsFailedProcessing() = runBlocking {
        var polls = 0
        val active = awaitGeminiFileActive(file("PROCESSING"), pollIntervalMillis = 0) { name ->
            assertEquals("files/test", name)
            polls += 1
            file(if (polls == 2) "ACTIVE" else "PROCESSING")
        }
        assertEquals(2, polls)
        assertEquals("ACTIVE", active.state().get().toString())
        try {
            awaitGeminiFileActive(file("PROCESSING"), 0) { file("FAILED") }
            error("Failed processing must be rejected")
        } catch (error: ProviderResponseException) {
            assertEquals(R.string.provider_file_failed, error.messageId)
        }
    }

    @Test
    fun fileReadiness_stopsPollingOnCancellation() = runBlocking {
        val polling = CompletableDeferred<Unit>()
        val result = async {
            awaitGeminiFileActive(file("PROCESSING"), 0) {
                polling.complete(Unit)
                CompletableDeferred<GenAiFile>().await()
            }
        }
        polling.await()
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
    }

    @Test
    fun fileReadiness_stopsAtTheTranscriptionDeadline() = runBlocking {
        try {
            withTimeout(50) {
                awaitGeminiFileActive(file("PROCESSING"), pollIntervalMillis = Long.MAX_VALUE) {
                    error("The pending poll must be cancelled by the deadline")
                }
            }
            error("Processing must be bounded by the transcription deadline")
        } catch (_: TimeoutCancellationException) {
            // The deadline ends processing even while the provider has no new state to return.
        }
    }

    @Test
    fun rateLimits_retryTransientQuotaButRejectExplicitDailyAndBillingLimits() {
        assertTrue(retries("Quota exceeded for requests per minute"))
        assertTrue(retries("Quota exceeded"))
        assertTrue(retries("daily limit; retryDelay: 3s"))
        assertFalse(retries("Daily limit reached"))
        assertFalse(retries("Billing must be enabled"))
    }

    private fun retries(message: String): Boolean =
        shouldRetryGeminiApiError(ApiException(429, "RESOURCE_EXHAUSTED", message))

    private fun candidate(finishReason: String, text: String): String =
        """
            {"candidates":[{"finishReason":"$finishReason",
            "content":{"parts":[{"text":${JSONObject.quote(text)}}]}}]}
        """.trimIndent()

    private fun file(state: String) = GenAiFile.fromJson(
        """{"name":"files/test","uri":"https://example.test/file","state":"$state"}""",
    )
}
