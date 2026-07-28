package com.andreacovelli.whispertranscriber.transcription

import okhttp3.HttpUrl

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
