package com.andreacovelli.whispertranscriber.transcription

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
            require(endpoint.isNotEmpty()) { "Enter an Azure Speech region or HTTPS endpoint." }

            if (!endpoint.contains("://")) {
                val region = MicrosoftSpeechRegion.parse(endpoint)
                val baseUrl = HttpUrl.Builder()
                    .scheme("https")
                    .host(region.endpointHost)
                    .build()
                return MicrosoftSpeechEndpoint(region.value, baseUrl)
            }

            val baseUrl = endpoint.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Enter a valid Azure Speech HTTPS endpoint.")
            require(baseUrl.scheme == "https") { "The Azure Speech endpoint must use HTTPS." }
            require(baseUrl.username.isEmpty() && baseUrl.password.isEmpty()) {
                "The Azure Speech endpoint must not include credentials."
            }
            require(AZURE_SPEECH_HOST_SUFFIXES.any(baseUrl.host::endsWith)) {
                "Enter an Azure Speech resource endpoint."
            }
            require(baseUrl.encodedPath == "/" && baseUrl.query == null && baseUrl.fragment == null) {
                "Enter only the Azure Speech base endpoint, without a path, query, or fragment."
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
            require(REGION_PATTERN.matches(region)) {
                "Enter a lowercase Azure Speech region such as eastus or westus."
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
