package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.security.useAsString
import com.andreacovelli.whispertranscriber.security.useGenAiClient
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal interface ProviderTranscriber {
    fun transcribe(): Flow<TranscriptionResult>
}

private const val NO_TEXT_FOUND = "No text found."

// Latest GA speech-to-text REST version per current Microsoft docs.
internal const val MICROSOFT_SPEECH_API_VERSION = "2025-10-15"
internal const val MICROSOFT_SPEECH_MAX_UPLOAD_BYTES = 250_000_000L

internal class MicrosoftSpeechTranscriber(
    private val apiKey: CharArray,
    private val audioFile: File,
    private val mimeType: String,
    private val region: String,
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
) : ProviderTranscriber {
    companion object {
        private const val MICROSOFT_SPEECH_MODEL = "mai-transcribe-1"
        private const val MICROSOFT_SPEECH_KEY_HEADER = "Ocp-Apim-Subscription-Key"
    }

    override fun transcribe(): Flow<TranscriptionResult> = flow {
        try {
            val normalizedRegion = region.trim().lowercase()
            require(normalizedRegion.isNotBlank()) { "Microsoft Speech region is not set." }
            require(isMicrosoftSpeechUploadSizeSupported(audioFile.length())) {
                "Microsoft MAI Transcribe supports audio files smaller than 250 MB."
            }

            emit(TranscriptionResult.Loading("Uploading audio ($mimeType) to Microsoft Speech..."))
            val endpoint =
                "https://$normalizedRegion.api.cognitive.microsoft.com/speechtotext/transcriptions:transcribe" +
                    "?api-version=$MICROSOFT_SPEECH_API_VERSION"
            val definitionJson = JSONObject()
                .put(
                    "enhancedMode",
                    JSONObject()
                        .put("enabled", true)
                        .put("model", MICROSOFT_SPEECH_MODEL),
                )
                .toString()
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody(mimeType.toMediaType()),
                )
                .addFormDataPart("definition", definitionJson)
                .build()

            val responseBody = apiKey.useAsString { key ->
                val request = Request.Builder()
                    .url(endpoint)
                    .header(MICROSOFT_SPEECH_KEY_HEADER, key)
                    .post(multipartBody)
                    .build()
                withContext(ioDispatcher) {
                    okHttpClient.newCall(request).execute().use { response ->
                        val bodyText = response.body.string()
                        if (!response.isSuccessful) {
                            throw MicrosoftSpeechHttpException(
                                code = response.code,
                                message = resolveMicrosoftSpeechErrorMessage(response.code, bodyText),
                            )
                        }
                        bodyText
                    }
                }
            }

            val response = try {
                JSONObject(responseBody)
            } catch (e: JSONException) {
                throw IllegalStateException("Failed to parse Microsoft Speech response: $responseBody", e)
            }
            emit(TranscriptionResult.Loading("Success"))
            emit(
                TranscriptionResult.Success(
                    TranscriptionData(
                        text = parseMicrosoftSpeechTranscriptText(response),
                        language = parseMicrosoftSpeechLanguage(response),
                        model = MICROSOFT_SPEECH_MODEL,
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: MicrosoftSpeechHttpException) {
            val error = if (shouldRetryHttp(e.code)) {
                TransientWorkException(e.message, e)
            } else {
                IllegalStateException(e.message, e)
            }
            emit(TranscriptionResult.Error(error))
        } catch (e: IOException) {
            emit(TranscriptionResult.Error(TransientWorkException("Microsoft Speech error: ${e.message}", e)))
        } catch (e: IllegalArgumentException) {
            emit(TranscriptionResult.Error(IllegalStateException(e.message ?: "Microsoft Speech request failed.", e)))
        } finally {
            apiKey.fill('\u0000')
        }
    }
}

private class MicrosoftSpeechHttpException(val code: Int, override val message: String) : IOException(message)

internal fun isMicrosoftSpeechUploadSizeSupported(sizeBytes: Long): Boolean =
    sizeBytes in 1 until MICROSOFT_SPEECH_MAX_UPLOAD_BYTES

internal class GeminiTranscriber(
    private val audioFile: File,
    private val mimeType: String,
    private val apiKey: CharArray,
    private val modelName: String,
    private val speakerDiarizationEnabled: Boolean,
    private val ioDispatcher: CoroutineDispatcher,
) : ProviderTranscriber {
    companion object {
        private const val TYPE_OBJECT = "OBJECT"
        private const val TYPE_STRING = "STRING"
    }

    override fun transcribe(): Flow<TranscriptionResult> = flow {
        var uploadedFile: GenAiFile? = null

        try {
            emit(TranscriptionResult.Loading("Uploading audio ($mimeType) via File API..."))
            apiKey.useGenAiClient { client ->
                uploadedFile = withContext(ioDispatcher) {
                    val uploadConfig = UploadFileConfig.builder().mimeType(mimeType).build()
                    client.files.upload(audioFile.absolutePath, uploadConfig)
                }
                val currentUploadedFile = checkNotNull(uploadedFile) { "File upload failed." }
                val audioPart = Part.fromUri(
                    currentUploadedFile.uri().orElseThrow { IllegalStateException("Missing URI") },
                    currentUploadedFile.mimeType().orElse(mimeType),
                )
                emit(TranscriptionResult.Loading("Transcribing audio..."))
                val config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .responseSchema(buildResponseSchema())
                    .build()
                val content = Content.fromParts(
                    Part.fromText(
                        buildPrompt(),
                    ),
                    audioPart,
                )
                val response = withContext(ioDispatcher) {
                    client.models.generateContent(modelName, content, config)
                }
                val json = response.text() ?: "{}"
                val obj = try {
                    JSONObject(json)
                } catch (e: JSONException) {
                    throw IllegalStateException("Failed to parse Gemini response: $json", e)
                }
                emit(TranscriptionResult.Loading("Success"))
                emit(
                    TranscriptionResult.Success(
                        TranscriptionData(
                            text = parseTranscriptText(obj),
                            language = obj.optString("language_code", "und"),
                            model = response.modelVersion().filter(String::isNotBlank).orElse(modelName),
                        ),
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            emit(TranscriptionResult.Error(TransientWorkException(resolveGeminiErrorMessage(e), e)))
        } catch (e: IllegalStateException) {
            emit(TranscriptionResult.Error(IllegalStateException(resolveGeminiErrorMessage(e), e)))
        } catch (e: IllegalArgumentException) {
            emit(TranscriptionResult.Error(IllegalStateException(resolveGeminiErrorMessage(e), e)))
        } finally {
            uploadedFile?.let { remoteFile ->
                runCatching {
                    apiKey.useGenAiClient { client ->
                        withContext(NonCancellable + ioDispatcher) {
                            remoteFile.name().orElse(null)?.let { remoteFileName ->
                                client.files.delete(remoteFileName, null)
                            }
                        }
                    }
                }.onFailure { throwable ->
                    if (throwable !is CancellationException) {
                        android.util.Log.w(
                            "TranscriptionWorker",
                            "Failed to delete remote Gemini file: ${remoteFile.name().orElse("[name not available]")}",
                            throwable,
                        )
                    }
                }
            }
            apiKey.fill('\u0000')
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

    private fun buildPrompt(): String = if (speakerDiarizationEnabled) {
        """
        Transcribe the provided audio and return only a compact JSON object with fields:
        language_code (BCP-47 like en-US) and text.
        When multiple speakers can be distinguished, format text with speaker labels on separate lines,
        for example:
        Speaker A: Hello
        Speaker B: Hi
        If speaker changes cannot be inferred reliably, return the plain transcript text without labels.
        Do not include any extra commentary.
        """.trimIndent()
    } else {
        "Transcribe the provided audio. Return only a compact JSON object with fields: language_code (BCP-47 like en-US) and text (full transcript). Do not include any extra commentary."
    }

    private fun parseTranscriptText(obj: JSONObject): String = parseTranscriptTextValue(
        transcriptText = obj.optString("text"),
        speakerDiarizationEnabled = speakerDiarizationEnabled,
    )

    private fun resolveGeminiErrorMessage(
        error: Throwable,
        httpCode: Int? = extractHttpStatusFromMessage(error),
    ): String = httpCode?.let { friendlyHttpError(it, "Gemini") }
        ?: "Gemini error: ${error.message ?: error::class.java.simpleName}"
}

internal fun friendlyHttpError(code: Int, provider: String): String = when (code) {
    401 -> "Unauthorized. Check your $provider API key."
    429 -> "Rate limit exceeded. Please wait a moment and try again."
    in 500..599 -> "Server error (HTTP $code). $provider's servers are experiencing issues."
    else -> "HTTP $code: unexpected error from $provider."
}

internal fun resolveMicrosoftSpeechErrorMessage(code: Int, bodyText: String): String = when (code) {
    401 -> "Unauthorized. Check your Microsoft Speech API key."
    404 -> "Microsoft Speech endpoint not found. Check the configured region."
    429 -> friendlyHttpError(code, "Microsoft Speech")
    in 500..599 -> friendlyHttpError(code, "Microsoft Speech")
    else -> extractMicrosoftSpeechServiceMessage(bodyText)
        ?.let { "HTTP $code: $it" }
        ?: "HTTP $code: unexpected error from Microsoft Speech."
}

internal fun parseMicrosoftSpeechTranscriptText(response: JSONObject): String {
    val combinedText = response.optJSONArray("combinedPhrases")
        ?.joinTexts()
        .orEmpty()
        .trim()
    if (combinedText.isNotBlank()) return combinedText

    return response.optJSONArray("phrases")
        ?.joinTexts()
        .orEmpty()
        .trim()
        .ifBlank { NO_TEXT_FOUND }
}

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

internal fun shouldRetryHttp(code: Int): Boolean = code == 429 || code in 500..599

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
        return transcriptText.trim().ifBlank { NO_TEXT_FOUND }
    }

    return transcriptText.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(separator = "\n")
        .ifBlank { NO_TEXT_FOUND }
}
