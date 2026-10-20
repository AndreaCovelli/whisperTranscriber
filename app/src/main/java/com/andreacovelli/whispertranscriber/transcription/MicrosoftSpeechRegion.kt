package com.andreacovelli.whispertranscriber.transcription

import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserFacingIllegalArgumentException
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.requireUserFacing
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MicrosoftSpeechEndpoint private constructor(val value: String, private val baseUrl: HttpUrl) {
    fun buildEndpoint(encodedPath: String, apiVersion: String): HttpUrl = baseUrl.newBuilder()
        .addEncodedPathSegments(encodedPath)
        .addQueryParameter("api-version", apiVersion)
        .build()

    companion object {
        private val AZURE_SPEECH_HOST_SUFFIXES = listOf(
            ".cognitiveservices.azure.com",
            ".cognitiveservices.azure.us",
            ".cognitiveservices.azure.cn",
            ".api.cognitive.microsoft.com",
            ".api.cognitive.microsoft.us",
            ".api.cognitive.azure.cn",
        )

        fun parse(rawEndpoint: String): MicrosoftSpeechEndpoint {
            val endpoint = rawEndpoint.trim()
            requireUserFacing(endpoint.isNotEmpty()) { UserMessage(R.string.microsoft_speech_endpoint_required) }

            if (!endpoint.contains("://")) {
                val region = MicrosoftSpeechRegion.parse(endpoint)
                val baseUrl = HttpUrl.Builder()
                    .scheme("https")
                    .host(region.endpointHost)
                    .build()
                return MicrosoftSpeechEndpoint(region.value, baseUrl)
            }

            val baseUrl = endpoint.toHttpUrlOrNull()
                ?: throw UserFacingIllegalArgumentException(UserMessage(R.string.microsoft_speech_endpoint_invalid))
            requireUserFacing(baseUrl.scheme == "https") { UserMessage(R.string.microsoft_speech_endpoint_https) }
            requireUserFacing(baseUrl.username.isEmpty() && baseUrl.password.isEmpty()) {
                UserMessage(R.string.microsoft_speech_endpoint_credentials)
            }
            requireUserFacing(AZURE_SPEECH_HOST_SUFFIXES.any(baseUrl.host::endsWith)) {
                UserMessage(R.string.microsoft_speech_endpoint_not_azure)
            }
            requireUserFacing(baseUrl.encodedPath == "/" && baseUrl.query == null && baseUrl.fragment == null) {
                UserMessage(R.string.microsoft_speech_endpoint_base_only)
            }
            return MicrosoftSpeechEndpoint(
                value = baseUrl.toString().removeSuffix("/"),
                baseUrl = baseUrl,
            )
        }

        fun parseOrNull(rawEndpoint: String): MicrosoftSpeechEndpoint? = try {
            parse(rawEndpoint)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

@JvmInline
value class MicrosoftSpeechRegion private constructor(val value: String) {
    val endpointHost: String
        get() = "$value.api.cognitive.microsoft.com"

    fun buildEndpoint(encodedPath: String, apiVersion: String): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host(endpointHost)
        .addEncodedPathSegments(encodedPath)
        .addQueryParameter("api-version", apiVersion)
        .build()

    companion object {
        private val REGION_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

        fun parse(rawRegion: String): MicrosoftSpeechRegion {
            val region = rawRegion.trim()
            requireUserFacing(REGION_PATTERN.matches(region)) {
                UserMessage(R.string.microsoft_speech_region_invalid)
            }
            return MicrosoftSpeechRegion(region)
        }

        fun parseOrNull(rawRegion: String): MicrosoftSpeechRegion? = try {
            parse(rawRegion)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
