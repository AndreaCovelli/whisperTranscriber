package com.andreacovelli.whispertranscriber.transcription

import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserFacingException
import com.andreacovelli.whispertranscriber.UserFacingIllegalStateException
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.security.GeminiApiFailure
import com.andreacovelli.whispertranscriber.security.GenAiClientFactory
import com.andreacovelli.whispertranscriber.security.useAsString
import com.andreacovelli.whispertranscriber.security.useGenAiClient
import com.andreacovelli.whispertranscriber.userMessageOr
import com.google.genai.errors.ApiException
import com.google.genai.errors.GenAiIOException
import com.google.genai.types.Content
import com.google.genai.types.File as GenAiFile
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.UploadFileConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class TransientWorkException(override val userMessage: UserMessage, cause: Throwable? = null) :
    IOException("Transient provider failure", cause),
    UserFacingException

/** [speakerLabels] reports whether the transcript contains speaker labels. */
data class TranscriptionData(
    val text: String,
    val language: String,
    val model: String,
    val speakerLabels: Boolean = false,
)

sealed interface TranscriptionResult {
    data class Loading(val message: UserMessage) : TranscriptionResult
    data class Success(val data: TranscriptionData) : TranscriptionResult
    data class Error(val exception: Exception) : TranscriptionResult
}

internal interface ProviderTranscriber {
    fun transcribe(): Flow<TranscriptionResult>
}

// Microsoft documents this as the latest generally available Speech-to-Text REST API version.
internal const val MICROSOFT_SPEECH_API_VERSION = "2025-10-15"
internal const val MICROSOFT_MAI_TRANSCRIBE_MODEL = "MAI-Transcribe-2"

internal class MicrosoftSpeechTranscriber(
    private val apiKey: CharArray,
    private val audioFile: File,
    private val mimeType: String,
    private val endpoint: MicrosoftSpeechEndpoint,
    private val modelName: String,
    private val speakerDiarizationEnabled: Boolean,
    private val speakerLabelPrefix: String,
    private val hints: TranscriptionHints,
    private val audioInspection: AudioInspection,
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
) : ProviderTranscriber {
    companion object {
        private const val MICROSOFT_SPEECH_KEY_HEADER = "Ocp-Apim-Subscription-Key"
    }

    override fun transcribe(): Flow<TranscriptionResult> = flow {
        try {
            validateMicrosoftSpeechAudio(audioInspection)

            emit(
                TranscriptionResult.Loading(
                    UserMessage(R.string.transcription_status_uploading_microsoft, listOf(mimeType)),
                ),
            )
            var diarized = speakerDiarizationEnabled
            val responseBody = try {
                requestTranscription(diarized)
            } catch (e: MicrosoftSpeechHttpException) {
                if (!diarized || !shouldRetryMicrosoftSpeechWithoutDiarization(e.code, audioInspection.durationUs)) {
                    throw e
                }
                diarized = false
                emit(TranscriptionResult.Loading(UserMessage(R.string.transcription_status_diarization_fallback)))
                requestTranscription(diarized)
            }

            val response = try {
                JSONObject(responseBody)
            } catch (_: JSONException) {
                throw ProviderResponseException(R.string.provider_response_invalid)
            }
            val text = if (diarized) {
                parseMicrosoftSpeechDiarizedTranscriptText(response, speakerLabelPrefix)
            } else {
                parseMicrosoftSpeechTranscriptText(response)
            }
            emit(
                TranscriptionResult.Success(
                    TranscriptionData(
                        text = text,
                        language = parseMicrosoftSpeechLanguage(response),
                        model = modelName,
                        speakerLabels = diarized && hasSpeakerLabels(text, speakerLabelPrefix),
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderResponseException) {
            emit(TranscriptionResult.Error(e))
        } catch (e: MicrosoftSpeechHttpException) {
            val error = if (shouldRetryHttp(e.code)) {
                TransientWorkException(e.userMessage, e)
            } else {
                UserFacingIllegalStateException(e.userMessage, e)
            }
            emit(TranscriptionResult.Error(error))
        } catch (e: IOException) {
            emit(TranscriptionResult.Error(TransientWorkException(UserMessage(R.string.provider_request_failed), e)))
        } catch (e: IllegalArgumentException) {
            emit(
                TranscriptionResult.Error(
                    UserFacingIllegalStateException(e.userMessageOr(R.string.provider_request_failed), e),
                ),
            )
        } finally {
            apiKey.fill('\u0000')
        }
    }

    private suspend fun requestTranscription(diarized: Boolean): String {
        val transcribeUrl = endpoint.buildEndpoint(
            encodedPath = "speechtotext/transcriptions:transcribe",
            apiVersion = MICROSOFT_SPEECH_API_VERSION,
        )
        val definitionJson = buildMicrosoftSpeechDefinition(modelName, diarized, hints).toString()
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                audioFile.name,
                audioFile.asRequestBody(mimeType.toMediaType()),
            )
            .addFormDataPart("definition", definitionJson)
            .build()

        return apiKey.useAsString { key ->
            val request = Request.Builder()
                .url(transcribeUrl)
                .header(MICROSOFT_SPEECH_KEY_HEADER, key)
                .post(multipartBody)
                .build()
            withContext(ioDispatcher) {
                okHttpClient.newCall(request).awaitResult { response ->
                    val bodyText = response.body.string()
                    if (!response.isSuccessful) {
                        throw MicrosoftSpeechHttpException(
                            code = response.code,
                            userMessage = resolveMicrosoftSpeechErrorMessage(response.code, bodyText),
                        )
                    }
                    bodyText
                }
            }
        }
    }
}

private class MicrosoftSpeechHttpException(val code: Int, val userMessage: UserMessage) : IOException("HTTP $code")

internal class GeminiTranscriber(
    private val audioFile: File,
    private val mimeType: String,
    private val apiKey: CharArray,
    private val modelName: String,
    private val speakerDiarizationEnabled: Boolean,
    private val speakerLabelPrefix: String,
    private val hints: TranscriptionHints,
    private val clientFactory: GenAiClientFactory,
    private val ioDispatcher: CoroutineDispatcher,
) : ProviderTranscriber {
    companion object {
        private const val TYPE_OBJECT = "OBJECT"
        private const val TYPE_STRING = "STRING"
        private const val GEMINI_TRANSCRIPTION_TIMEOUT_MILLIS = 600_000
    }

    override fun transcribe(): Flow<TranscriptionResult> = flow {
        var uploadedFile: GenAiFile? = null

        try {
            emit(
                TranscriptionResult.Loading(
                    UserMessage(R.string.transcription_status_uploading_gemini, listOf(mimeType)),
                ),
            )
            withTimeout(GEMINI_TRANSCRIPTION_TIMEOUT_MILLIS.toLong()) {
                apiKey.useGenAiClient(clientFactory, ioDispatcher, GEMINI_TRANSCRIPTION_TIMEOUT_MILLIS) { operation ->
                    val initial = operation.call { client ->
                        val config = UploadFileConfig.builder().mimeType(mimeType).build()
                        client.files.upload(audioFile.absolutePath, config).also { uploadedFile = it }
                    }
                    val activeFile = awaitGeminiFileActive(initial) { name ->
                        operation.call { it.files.get(name, null) }
                    }
                    val audioPart = Part.fromUri(activeFile.uri().orElseThrow(), activeFile.mimeType().orElse(mimeType))
                    val config = GenerateContentConfig.builder()
                        .responseMimeType("application/json")
                        .responseSchema(buildResponseSchema())
                        .build()
                    val content = Content.fromParts(Part.fromText(buildPrompt()), audioPart)
                    val response = operation.call { it.models.generateContent(modelName, content, config) }
                    parseGeminiTranscription(
                        validatedGeminiText(response),
                        speakerDiarizationEnabled,
                        speakerLabelPrefix,
                        response.modelVersion().filter(String::isNotBlank).orElse(modelName),
                    )
                }
            }.let { emit(TranscriptionResult.Success(it)) }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            emit(TranscriptionResult.Error(TransientWorkException(UserMessage(R.string.gemini_request_timed_out))))
        } catch (e: ProviderResponseException) {
            emit(TranscriptionResult.Error(e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            emit(TranscriptionResult.Error(TransientWorkException(resolveGeminiErrorMessage(e), e)))
        } catch (e: GenAiIOException) {
            emit(TranscriptionResult.Error(TransientWorkException(resolveGeminiErrorMessage(e), e)))
        } catch (e: ApiException) {
            val message = resolveGeminiErrorMessage(e, e.code())
            val error = if (shouldRetryGeminiApiError(e)) {
                TransientWorkException(message, e)
            } else {
                UserFacingIllegalStateException(message, e)
            }
            emit(TranscriptionResult.Error(error))
        } catch (e: IllegalStateException) {
            emit(TranscriptionResult.Error(UserFacingIllegalStateException(resolveGeminiErrorMessage(e), e)))
        } catch (e: IllegalArgumentException) {
            emit(TranscriptionResult.Error(UserFacingIllegalStateException(resolveGeminiErrorMessage(e), e)))
        } finally {
            uploadedFile?.let { remoteFile -> deleteUploadedFileBestEffort(remoteFile) }
            apiKey.fill('\u0000')
        }
    }

    // The SDK's cleanup path can throw several nonfatal exception types. Cleanup must not hide the
    // transcription result, while fatal JVM Errors must still escape.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun deleteUploadedFileBestEffort(remoteFile: GenAiFile) {
        try {
            withContext(NonCancellable + ioDispatcher) {
                withTimeout(30_000) {
                    apiKey.useGenAiClient(clientFactory, ioDispatcher, timeoutMillis = 30_000) { operation ->
                        remoteFile.name().orElse(null)?.let { name ->
                            operation.call { it.files.delete(name, null) }
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            // Preserve the cancellation already being processed by the transcription flow.
        } catch (_: Exception) {
            android.util.Log.w("TranscriptionWorker", "Failed to delete remote Gemini file")
        }
    }

    private fun buildResponseSchema(): Schema = Schema.builder().type(TYPE_OBJECT)
        .properties(
            mapOf(
                "language_code" to Schema.builder().type(TYPE_STRING).build(),
                "text" to Schema.builder().type(TYPE_STRING).build(),
            ),
        )
        .required(listOf("language_code", "text"))
        .build()

    private fun buildPrompt(): String =
        buildGeminiTranscriptionPrompt(speakerDiarizationEnabled, speakerLabelPrefix, hints)

    private fun resolveGeminiErrorMessage(
        error: Throwable,
        httpCode: Int? = extractHttpStatusFromMessage(error),
    ): UserMessage = httpCode?.let { code ->
        if (code == 429 && isQuotaExhaustedMessage(error.message)) {
            UserMessage(R.string.gemini_quota_exhausted)
        } else {
            friendlyHttpError(code, UserMessage(R.string.transcription_provider_gemini))
        }
    }
        ?: UserMessage(R.string.provider_request_failed)
}

internal fun friendlyHttpError(code: Int, provider: UserMessage): UserMessage = when (code) {
    401 -> UserMessage(R.string.http_error_unauthorized, listOf(provider))
    429 -> UserMessage(R.string.http_error_rate_limited)
    in 500..599 -> UserMessage(R.string.http_error_server, listOf(code, provider))
    else -> UserMessage(R.string.http_error_unexpected, listOf(code, provider))
}

internal fun resolveMicrosoftSpeechErrorMessage(code: Int, bodyText: String): UserMessage = when {
    code == 401 -> UserMessage(R.string.microsoft_speech_unauthorized)
    code == 404 -> UserMessage(R.string.microsoft_speech_endpoint_not_found)
    code == 429 && isMicrosoftSpeechResourceExhaustedMessage(extractMicrosoftSpeechServiceMessage(bodyText)) ->
        UserMessage(R.string.microsoft_speech_resource_exhausted)
    else -> friendlyHttpError(code, UserMessage(R.string.provider_name_microsoft_speech))
}

internal fun parseMicrosoftSpeechTranscriptText(response: JSONObject): String {
    if (!response.has("combinedPhrases") && !response.has("phrases")) {
        throw ProviderResponseException(R.string.provider_response_invalid)
    }
    listOf("combinedPhrases", "phrases").filter(response::has).forEach { field ->
        val phrases =
            response.opt(field) as? JSONArray ?: throw ProviderResponseException(R.string.provider_response_invalid)
        for (index in 0 until phrases.length()) {
            val phrase =
                phrases.optJSONObject(index) ?: throw ProviderResponseException(R.string.provider_response_invalid)
            if (phrase.opt("text") !is String) throw ProviderResponseException(R.string.provider_response_invalid)
        }
    }
    val combinedText = response.optJSONArray("combinedPhrases")
        ?.joinTexts()
        .orEmpty()
        .trim()
    if (combinedText.isNotBlank()) return combinedText

    return response.optJSONArray("phrases")
        ?.joinTexts()
        .orEmpty()
        .trim()
}

// MAI-Transcribe-2 diarization supports shorter recordings than plain transcription. Microsoft
// does not publish the exact limit; longer diarized requests have been reported to fail with
// HTTP 408, 500, or 503 from about 15 minutes, while the same audio transcribes without diarization.
internal const val MICROSOFT_DIARIZATION_FALLBACK_MIN_DURATION_US = 10L * 60L * 1_000_000L

internal fun shouldRetryMicrosoftSpeechWithoutDiarization(code: Int, durationUs: Long): Boolean =
    durationUs >= MICROSOFT_DIARIZATION_FALLBACK_MIN_DURATION_US && code in setOf(408, 500, 503)

internal fun buildMicrosoftSpeechDefinition(
    modelName: String,
    speakerDiarizationEnabled: Boolean,
    hints: TranscriptionHints = TranscriptionHints(),
): JSONObject = JSONObject()
    .put(
        "enhancedMode",
        JSONObject()
            .put("enabled", true)
            .put("model", modelName),
    )
    .apply {
        if (speakerDiarizationEnabled) put("diarization", JSONObject().put("enabled", true))
        // Microsoft treats a locale as a strong constraint, so it is only sent when the user chose one.
        if (hints.languageCode.isNotEmpty()) put("locales", JSONArray().put(hints.languageCode))
        if (hints.vocabulary.isNotEmpty()) {
            put("phraseList", JSONObject().put("phrases", JSONArray(hints.vocabulary)))
        }
    }

/**
 * Formats diarized phrases like the Gemini diarization output: consecutive phrases from the same
 * speaker are merged into one "<prefix> A: text" line, with letters assigned in order of first
 * appearance. Falls back to the plain transcript when the response carries no speaker information.
 */
internal fun parseMicrosoftSpeechDiarizedTranscriptText(response: JSONObject, speakerLabelPrefix: String): String {
    val plainText = parseMicrosoftSpeechTranscriptText(response)
    val phrases = response.optJSONArray("phrases") ?: return plainText
    val speakerPhrases = (0 until phrases.length()).mapNotNull { index ->
        val phrase = phrases.optJSONObject(index) ?: return@mapNotNull null
        val text = phrase.optString("text").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        val speaker = (phrase.opt("speaker") as? Number)?.toInt()
        speaker to text
    }
    if (speakerPhrases.none { (speaker, _) -> speaker != null }) return plainText

    val normalizedPrefix = speakerLabelPrefix.trim().ifBlank { "Speaker" }
    val speakerLabels = linkedMapOf<Int, String>()
    val lines = mutableListOf<Pair<Int?, StringBuilder>>()
    speakerPhrases.forEach { (speaker, text) ->
        val last = lines.lastOrNull()
        if (last != null && last.first == speaker) {
            last.second.append(' ').append(text)
        } else {
            lines += speaker to StringBuilder(text)
        }
    }
    return lines.joinToString(separator = "\n") { (speaker, text) ->
        if (speaker == null) {
            text.toString()
        } else {
            val label = speakerLabels.getOrPut(speaker) { microsoftSpeechSpeakerLabel(speakerLabels.size) }
            "$normalizedPrefix $label: $text"
        }
    }
}

private fun microsoftSpeechSpeakerLabel(index: Int): String =
    if (index < ALPHABET_SIZE) ('A' + index).toString() else (index + 1).toString()

private const val ALPHABET_SIZE = 26

internal fun parseMicrosoftSpeechLanguage(response: JSONObject): String = response.optJSONArray("phrases")
    ?.let(::selectDominantMicrosoftSpeechLocale)
    .orEmpty()
    .ifBlank { "und" }

internal fun extractMicrosoftSpeechServiceMessage(bodyText: String): String? = runCatching {
    val body = JSONObject(bodyText)
    val error = body.optJSONObject("error")
    error?.optString("message")
        ?.takeIf(String::isNotBlank)
        ?: error?.optJSONObject("innerError")
            ?.optString("message")
            ?.takeIf(String::isNotBlank)
}.getOrNull()

internal fun isMicrosoftSpeechResourceExhaustedMessage(message: String?): Boolean {
    val normalized = message.orEmpty().lowercase()
    if (normalized.isBlank()) return false

    return listOf(
        "resource exhausted",
        "too many requests",
        "rate limit",
        "quota",
        "concurrent",
        "capacity",
    ).any(normalized::contains)
}

internal fun shouldRetryHttp(code: Int): Boolean = code == 429 || code in 500..599

internal fun shouldRetryGeminiApiError(error: ApiException): Boolean {
    if (error.code() == 429) {
        return (error as? GeminiApiFailure)?.retrySuggested == true ||
            error.message().orEmpty().contains("retryDelay", ignoreCase = true) ||
            error.message().orEmpty().contains("retryInfo", ignoreCase = true) ||
            !isQuotaExhaustedMessage(error.message())
    }
    return shouldRetryHttp(error.code())
}

internal fun isQuotaExhaustedMessage(message: String?): Boolean {
    val normalized = message.orEmpty().lowercase()
    if (normalized.isBlank()) return false

    return listOf(
        "billing",
        "daily limit",
        "per day",
        "per_day",
        "perday",
        "daily quota",
    ).any(normalized::contains)
}

internal fun extractHttpStatusFromMessage(error: Throwable): Int? =
    HTTP_STATUS_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    }

private val HTTP_STATUS_PATTERNS = listOf(
    Regex("""\bHTTP\s+([45]\d{2})\b""", RegexOption.IGNORE_CASE),
    Regex("""\bstatus(?:\s+code)?\s*[:=]?\s*([45]\d{2})\b""", RegexOption.IGNORE_CASE),
    Regex("""\bcode\s*[:=]?\s*([45]\d{2})\b""", RegexOption.IGNORE_CASE),
)

private fun JSONArray.joinTexts(): String = buildList {
    for (index in 0 until length()) {
        optJSONObject(index)
            ?.optString("text")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
    }
}.joinToString(separator = "\n")

private fun selectDominantMicrosoftSpeechLocale(phrases: JSONArray): String? {
    val localeCounts = linkedMapOf<String, Int>()
    for (index in 0 until phrases.length()) {
        val locale = phrases.optJSONObject(index)
            ?.optString("locale")
            .orEmpty()
            .trim()
            .takeIf(String::isNotBlank)
            ?: continue
        localeCounts[locale] = localeCounts.getOrDefault(locale, 0) + 1
    }

    return localeCounts.entries
        .maxByOrNull(Map.Entry<String, Int>::value)
        ?.key
}

internal fun parseTranscriptTextValue(transcriptText: String, speakerDiarizationEnabled: Boolean): String {
    if (!speakerDiarizationEnabled) {
        return transcriptText.trim()
    }

    return transcriptText.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(separator = "\n")
}

/** Whether a line of [text] starts with a label such as "Speaker A:", as the providers write them. */
internal fun hasSpeakerLabels(text: String, speakerLabelPrefix: String): Boolean {
    val prefix = Regex.escape(speakerLabelPrefix.trim().ifBlank { "Speaker" })
    return Regex("^$prefix [A-Za-z0-9]+:", RegexOption.MULTILINE).containsMatchIn(text)
}

internal fun buildGeminiTranscriptionPrompt(
    speakerDiarizationEnabled: Boolean,
    speakerLabelPrefix: String,
    hints: TranscriptionHints,
): String {
    val base = if (speakerDiarizationEnabled) {
        buildGeminiSpeakerDiarizationPrompt(speakerLabelPrefix)
    } else {
        "Transcribe the provided audio. Return only a compact JSON object with fields: language_code (BCP-47 like en-US) and text (full transcript). Do not include any extra commentary."
    }
    val hintInstructions = buildGeminiHintInstructions(hints)
    return if (hintInstructions.isEmpty()) base else "$base\n$hintInstructions"
}

internal fun buildGeminiSpeakerDiarizationPrompt(speakerLabelPrefix: String): String {
    val normalizedPrefix = speakerLabelPrefix.trim().ifBlank { "Speaker" }
    return """
        Transcribe the provided audio and return only a compact JSON object with fields:
        language_code (BCP-47 like en-US) and text.
        When multiple speakers can be distinguished, format text with speaker labels on separate lines,
        for example:
        $normalizedPrefix A: Hello
        $normalizedPrefix B: Hi
        If speaker changes cannot be inferred reliably, return the plain transcript text without labels.
        Do not include any extra commentary.
    """.trimIndent()
}
