package com.andreacovelli.whispertranscriber

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

data class ElevenLabsResponse(
    @SerializedName("text") val text: String,
    @SerializedName("language_code") val languageCode: String?,
    @SerializedName("language_probability") val languageProbability: Double?,
    @SerializedName("words") val words: List<ElevenLabsWord>?,
)

data class ElevenLabsWord(
    @SerializedName("text") val text: String,
    @SerializedName("start") val start: Double,
    @SerializedName("end") val end: Double,
    @SerializedName("type") val type: String,
    @SerializedName("speaker_id") val speakerId: String?,
)

interface ElevenLabsApi {
    @Multipart
    @POST("v1/speech-to-text")
    suspend fun transcribe(
        @Header("xi-api-key") apiKey: String,
        @Part file: MultipartBody.Part,
        @Part modelId: MultipartBody.Part,
    ): ElevenLabsResponse

    @retrofit2.http.GET("v1/user")
    suspend fun getUser(@Header("xi-api-key") apiKey: String): retrofit2.Response<Void>
}
