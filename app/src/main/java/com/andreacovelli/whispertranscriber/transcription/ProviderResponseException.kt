package com.andreacovelli.whispertranscriber.transcription

import androidx.annotation.StringRes
import com.andreacovelli.whispertranscriber.R
import com.google.genai.types.File as GenAiFile
import com.google.genai.types.GenerateContentResponse
import kotlinx.coroutines.delay
import org.json.JSONException
import org.json.JSONObject

internal class ProviderResponseException(@param:StringRes val messageId: Int) :
    IllegalStateException("Invalid provider response")

internal fun validatedGeminiText(response: GenerateContentResponse): String {
    val blocked = response.promptFeedback().orElse(null)?.blockReason()?.orElse(null)?.toString()
    if (blocked != null && blocked != "BLOCKED_REASON_UNSPECIFIED") {
        throw ProviderResponseException(R.string.provider_response_blocked)
    }
    val candidate = response.candidates().orElse(emptyList()).singleOrNull()
        ?: throw ProviderResponseException(R.string.provider_response_invalid)
    when (candidate.finishReason().orElse(null)?.toString()) {
        "STOP" -> Unit
        "MAX_TOKENS" -> throw ProviderResponseException(R.string.provider_response_truncated)
        null, "FINISH_REASON_UNSPECIFIED" -> throw ProviderResponseException(R.string.provider_response_invalid)
        else -> throw ProviderResponseException(R.string.provider_response_blocked)
    }
    if (candidate.safetyRatings().orElse(emptyList()).any { it.blocked().orElse(false) }) {
        throw ProviderResponseException(R.string.provider_response_blocked)
    }
    val parts = candidate.content().orElse(null)?.parts()?.orElse(emptyList()).orEmpty()
        .filterNot { it.thought().orElse(false) }
    if (parts.isEmpty() || parts.any { !it.text().isPresent }) {
        throw ProviderResponseException(R.string.provider_response_invalid)
    }
    return parts.joinToString("") { it.text().orElse("") }.trim().ifBlank {
        throw ProviderResponseException(R.string.provider_response_invalid)
    }
}

internal fun parseGeminiTranscription(json: String, diarization: Boolean, model: String): TranscriptionData {
    val obj = try {
        JSONObject(json)
    } catch (_: JSONException) {
        throw ProviderResponseException(R.string.provider_response_invalid)
    }
    val text = obj.opt("text") as? String ?: throw ProviderResponseException(R.string.provider_response_invalid)
    val language = (obj.opt("language_code") as? String)?.takeIf(String::isNotBlank)
        ?: throw ProviderResponseException(R.string.provider_response_invalid)
    return TranscriptionData(parseTranscriptTextValue(text, diarization), language, model)
}

internal suspend fun awaitGeminiFileActive(
    initial: GenAiFile,
    pollIntervalMillis: Long = 2_000,
    refresh: suspend (String) -> GenAiFile,
): GenAiFile {
    val name = initial.name().orElse(null)?.takeIf(String::isNotBlank)
        ?: throw ProviderResponseException(R.string.provider_response_invalid)
    var file = initial
    while (true) {
        when (file.state().orElse(null)?.toString()) {
            "ACTIVE" -> {
                if (file.uri().orElse("").isBlank()) throw ProviderResponseException(R.string.provider_response_invalid)
                return file
            }
            "FAILED" -> throw ProviderResponseException(R.string.provider_file_failed)
            "PROCESSING" -> {
                delay(pollIntervalMillis)
                file = refresh(name)
            }
            else -> throw ProviderResponseException(R.string.provider_response_invalid)
        }
    }
}
