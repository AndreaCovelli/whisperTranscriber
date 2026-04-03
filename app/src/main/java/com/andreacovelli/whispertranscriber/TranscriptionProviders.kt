package com.andreacovelli.whispertranscriber

import android.net.Uri
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.File as GenAiFile
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.UploadFileConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONException
import org.json.JSONObject
import retrofit2.HttpException

internal interface ProviderTranscriber {
    fun transcribe(): Flow<TranscriptionResult>
}

internal class ElevenLabsTranscriber(
    private val source: Any,
    private val apiKey: CharArray,
    private val createRequestBodyFromUri: (Uri) -> okhttp3.RequestBody,
) : ProviderTranscriber {
    companion object {
        private const val ELEVEN_LABS_MODEL_ID = "scribe_v2"
    }

    override fun transcribe(): Flow<TranscriptionResult> = flow {
        try {
            emit(TranscriptionResult.Loading("Uploading and transcribing..."))
            val requestFileBody = when (source) {
                is File -> source.asRequestBody("application/octet-stream".toMediaType())
                is Uri -> createRequestBodyFromUri(source)
                else -> throw IllegalArgumentException("Unsupported source type")
            }
            val filePart = MultipartBody.Part.createFormData("file", "audio_file", requestFileBody)
            val modelIdPart = MultipartBody.Part.createFormData("model_id", ELEVEN_LABS_MODEL_ID)
            val response = RetrofitClient.api.transcribe(String(apiKey), filePart, modelIdPart)
            emit(TranscriptionResult.Loading("Success"))
            emit(
                TranscriptionResult.Success(
                    TranscriptionData(
                        text = response.text,
                        language = response.languageCode ?: "Unknown",
                        model = "ElevenLabs Scribe v2",
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val error = if (shouldRetryHttp(e.code())) {
                TransientWorkException(friendlyHttpError(e.code(), "ElevenLabs"), e)
            } else {
                IllegalStateException(friendlyHttpError(e.code(), "ElevenLabs"), e)
            }
            emit(TranscriptionResult.Error(error))
        } catch (e: IOException) {
            emit(TranscriptionResult.Error(TransientWorkException("Network or API error: ${e.message}", e)))
        } catch (e: IllegalArgumentException) {
            emit(TranscriptionResult.Error(IllegalStateException("Network or API error: ${e.message}", e)))
        } finally {
            apiKey.fill('\u0000')
        }
    }
}

internal class GeminiTranscriber(
    private val audioFile: File,
    private val mimeType: String,
    private val apiKey: CharArray,
    private val modelName: String,
) : ProviderTranscriber {
    companion object {
        private const val TYPE_OBJECT = "OBJECT"
        private const val TYPE_STRING = "STRING"
    }

    override fun transcribe(): Flow<TranscriptionResult> = flow {
        val client = Client.builder().apiKey(String(apiKey)).build()
        var uploadedFile: GenAiFile? = null

        try {
            emit(TranscriptionResult.Loading("Uploading audio ($mimeType) via File API..."))
            uploadedFile = withContext(Dispatchers.IO) {
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
                    "Transcribe the provided audio. Return only a compact JSON object with fields: language_code (BCP-47 like en-US) and text (full transcript). Do not include any extra commentary.",
                ),
                audioPart,
            )
            val response = withContext(Dispatchers.IO) {
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
                        text = obj.optString("text").ifBlank { "No text found." },
                        language = obj.optString("language_code", "und"),
                        model = response.modelVersion().filter(String::isNotBlank).orElse(modelName),
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            emit(TranscriptionResult.Error(TransientWorkException(resolveGeminiErrorMessage(e), e)))
        } catch (e: IllegalStateException) {
            emit(TranscriptionResult.Error(IllegalStateException(resolveGeminiErrorMessage(e), e)))
        } catch (e: IllegalArgumentException) {
            emit(TranscriptionResult.Error(IllegalStateException(resolveGeminiErrorMessage(e), e)))
        } finally {
            apiKey.fill('\u0000')
            uploadedFile?.let { remoteFile ->
                runCatching {
                    withContext(NonCancellable + Dispatchers.IO) {
                        remoteFile.name().orElse(null)?.let { remoteFileName ->
                            client.files.delete(remoteFileName, null)
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
