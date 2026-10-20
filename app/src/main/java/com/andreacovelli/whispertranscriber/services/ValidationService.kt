package com.andreacovelli.whispertranscriber.services

import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.transcription.MicrosoftSpeechEndpoint
import com.andreacovelli.whispertranscriber.transcription.awaitResult
import com.andreacovelli.whispertranscriber.userMessageOr
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

data class KeyValidationResult(val errorMessage: UserMessage? = null) {
    val isValid: Boolean get() = errorMessage == null
}

data class RegionValidationResult(val normalizedRegion: String? = null, val errorMessage: UserMessage? = null) {
    val isValid: Boolean get() = errorMessage == null
}

data class CustomModelValidationResult(
    val displayName: String? = null,
    val normalizedModelName: String? = null,
    val errorMessage: UserMessage? = null,
) {
    val isValid: Boolean get() = errorMessage == null
}

private val GEMINI_MODEL_ID_PATTERN = Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?")

/**
 * Returns the bare Gemini model ID, accepting the `models/` resource prefix the SDK also accepts,
 * or null when the name could change the request path or query.
 */
internal fun normalizeGeminiModelName(modelName: String): String? = modelName.trim()
    .removePrefix("models/")
    .takeIf { name -> GEMINI_MODEL_ID_PATTERN.matches(name) && ".." !in name }

internal fun resolveMicrosoftSpeechKeyValidationResult(code: Int): KeyValidationResult = when (code) {
    200 -> KeyValidationResult()
    401, 403 -> KeyValidationResult(UserMessage(R.string.microsoft_speech_key_invalid))
    404 -> KeyValidationResult(UserMessage(R.string.microsoft_speech_endpoint_not_found))
    else -> KeyValidationResult(UserMessage(R.string.validation_failed_http, listOf(code)))
}

internal fun resolveMicrosoftSpeechKeyValidationResults(codes: List<Int>): KeyValidationResult {
    if (codes.isEmpty()) return KeyValidationResult(UserMessage(R.string.validation_failed))
    if (codes.any { it == 200 }) return KeyValidationResult()

    val authCode = codes.firstOrNull { it == 401 || it == 403 }
    if (authCode != null) return resolveMicrosoftSpeechKeyValidationResult(authCode)

    val reportedCode = codes.firstOrNull { it == 404 || it == 405 || it == 400 || it == 429 || it in 500..599 }
        ?: codes.first()
    return KeyValidationResult(UserMessage(R.string.validation_failed_http, listOf(reportedCode)))
}

internal fun resolveMicrosoftSpeechRegionValidationResults(
    codes: List<Int>,
    normalizedEndpoint: String,
): RegionValidationResult {
    if (codes.any { it in 200..299 || it == 401 || it == 403 }) {
        return RegionValidationResult(normalizedRegion = normalizedEndpoint)
    }

    val reportedCode = codes.firstOrNull { it == 404 || it == 405 || it == 400 || it == 429 || it in 500..599 }
        ?: codes.firstOrNull()
        ?: return RegionValidationResult(errorMessage = UserMessage(R.string.validation_failed))
    return RegionValidationResult(
        errorMessage = UserMessage(R.string.microsoft_speech_region_validation_failed_http, listOf(reportedCode)),
    )
}

internal fun resolveMicrosoftSpeechRegionIOExceptionMessage(normalizedRegion: String, error: IOException): UserMessage {
    val message = error.message.orEmpty()
    return if (error is UnknownHostException || message.contains("Unable to resolve host", ignoreCase = true)) {
        if (normalizedRegion.startsWith("https://")) {
            UserMessage(R.string.microsoft_speech_endpoint_unreachable, listOf(normalizedRegion))
        } else {
            UserMessage(R.string.microsoft_speech_region_unreachable, listOf(normalizedRegion))
        }
    } else {
        UserMessage(R.string.validation_network_failed)
    }
}

@Singleton
class ValidationService @Inject constructor(
    private val settings: ApiKeyManager,
    private val okHttpClient: OkHttpClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val GEMINI_MODELS_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val GEMINI_API_KEY_HEADER = "x-goog-api-key"
        private const val MICROSOFT_SPEECH_KEY_HEADER = "Ocp-Apim-Subscription-Key"
        private const val MICROSOFT_SPEECH_LOCALES_API_VERSION = "2025-10-15"
        private const val MICROSOFT_SPEECH_LOCALES_FALLBACK_API_VERSION = "2024-11-15"
    }

    suspend fun validateGeminiKey(rawKey: String): KeyValidationResult = try {
        val request = Request.Builder()
            .url(GEMINI_MODELS_BASE_URL)
            .header(GEMINI_API_KEY_HEADER, rawKey)
            .build()
        val result = withContext(ioDispatcher) {
            okHttpClient.newCall(request).awaitResult { response ->
                response.isSuccessful to response.code
            }
        }
        if (result.first) {
            KeyValidationResult()
        } else {
            KeyValidationResult(
                if (result.second == 400) {
                    UserMessage(R.string.gemini_key_invalid)
                } else {
                    UserMessage(R.string.validation_failed_http, listOf(result.second))
                },
            )
        }
    } catch (_: IOException) {
        KeyValidationResult(UserMessage(R.string.validation_network_failed))
    }

    suspend fun validateCustomModel(modelName: String): CustomModelValidationResult {
        val normalizedModelName = normalizeGeminiModelName(modelName)
            ?: return CustomModelValidationResult(
                errorMessage = UserMessage(R.string.gemini_model_name_invalid, listOf(modelName.trim())),
            )
        val keyChars = settings.getGeminiApiKey()
        if (keyChars == null || keyChars.isEmpty()) {
            val keyState = settings.geminiApiKeyStateFlow.first()
            val error = if (keyState is ApiKeyState.Error) {
                UserMessage(R.string.api_key_keystore_error, listOf(keyState.message))
            } else {
                UserMessage(R.string.gemini_key_required)
            }
            return CustomModelValidationResult(errorMessage = error)
        }

        return try {
            val request = Request.Builder()
                .url(GEMINI_MODELS_BASE_URL.toHttpUrl().newBuilder().addPathSegment(normalizedModelName).build())
                .header(GEMINI_API_KEY_HEADER, String(keyChars))
                .build()
            val (isSuccess, code, bodyText) = withContext(ioDispatcher) {
                okHttpClient.newCall(request).awaitResult { response ->
                    Triple(
                        response.isSuccessful,
                        response.code,
                        runCatching { response.body.string() }.getOrDefault(""),
                    )
                }
            }
            if (!isSuccess) {
                CustomModelValidationResult(
                    errorMessage = if (code == 404) {
                        UserMessage(R.string.gemini_model_not_found, listOf(normalizedModelName))
                    } else {
                        UserMessage(R.string.gemini_model_validation_failed_http, listOf(code))
                    },
                )
            } else {
                CustomModelValidationResult(
                    displayName = parseDisplayName(bodyText, normalizedModelName),
                    normalizedModelName = normalizedModelName,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            CustomModelValidationResult(errorMessage = UserMessage(R.string.validation_network_failed))
        } finally {
            keyChars.fill('\u0000')
        }
    }

    private fun parseDisplayName(bodyText: String, fallback: String): String = try {
        JSONObject(bodyText).optString("displayName", fallback)
    } catch (_: JSONException) {
        fallback
    }

    suspend fun validateMicrosoftSpeechKey(rawKey: String, region: String): KeyValidationResult {
        val validatedEndpoint = MicrosoftSpeechEndpoint.parseOrNull(region)
            ?: return KeyValidationResult(UserMessage(R.string.microsoft_speech_endpoint_required_first))

        return try {
            val codes = probeMicrosoftSpeechKey(validatedEndpoint, rawKey)
            resolveMicrosoftSpeechKeyValidationResults(codes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            KeyValidationResult(resolveMicrosoftSpeechRegionIOExceptionMessage(validatedEndpoint.value, e))
        }
    }

    suspend fun validateMicrosoftSpeechRegion(region: String): RegionValidationResult {
        val validatedEndpoint = try {
            MicrosoftSpeechEndpoint.parse(region)
        } catch (error: IllegalArgumentException) {
            return RegionValidationResult(
                errorMessage = error.userMessageOr(R.string.microsoft_speech_endpoint_required),
            )
        }

        return try {
            val codes = probeMicrosoftSpeechRegion(validatedEndpoint)
            resolveMicrosoftSpeechRegionValidationResults(codes, validatedEndpoint.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            RegionValidationResult(
                errorMessage = resolveMicrosoftSpeechRegionIOExceptionMessage(validatedEndpoint.value, e),
            )
        }
    }

    private fun buildMicrosoftSpeechValidationUrls(endpoint: MicrosoftSpeechEndpoint) = listOf(
        endpoint.buildEndpoint(
            encodedPath = "speechtotext/transcriptions/locales",
            apiVersion = MICROSOFT_SPEECH_LOCALES_API_VERSION,
        ),
        endpoint.buildEndpoint(
            encodedPath = "speechtotext/transcriptions/locales",
            apiVersion = MICROSOFT_SPEECH_LOCALES_FALLBACK_API_VERSION,
        ),
    )

    private suspend fun probeMicrosoftSpeechRegion(endpoint: MicrosoftSpeechEndpoint): List<Int> =
        probeMicrosoftSpeech(endpoint = endpoint, rawKey = null)

    private suspend fun probeMicrosoftSpeechKey(endpoint: MicrosoftSpeechEndpoint, rawKey: String): List<Int> =
        probeMicrosoftSpeech(endpoint = endpoint, rawKey = rawKey)

    private suspend fun probeMicrosoftSpeech(endpoint: MicrosoftSpeechEndpoint, rawKey: String?): List<Int> =
        withContext(ioDispatcher) {
            coroutineScope {
                buildMicrosoftSpeechValidationUrls(endpoint).map { url ->
                    async {
                        val requestBuilder = Request.Builder()
                            .url(url)
                            .get()
                        if (rawKey != null) {
                            requestBuilder.header(MICROSOFT_SPEECH_KEY_HEADER, rawKey)
                        }
                        okHttpClient.newCall(requestBuilder.build()).awaitResult { response -> response.code }
                    }
                }.awaitAll()
            }
        }
}
