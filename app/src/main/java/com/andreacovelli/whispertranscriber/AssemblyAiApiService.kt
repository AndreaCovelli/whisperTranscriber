package com.andreacovelli.whispertranscriber

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

data class UploadResponse(
    @SerializedName("upload_url") val uploadUrl: String
)

data class TranscriptRequest(
    @SerializedName("audio_url") val audioUrl: String,
    @SerializedName("language_detection") val languageDetection: Boolean = true
)

data class TranscriptResponse(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String,
    @SerializedName("text") val text: String?,
    @SerializedName("error") val error: String?,
    @SerializedName("language_code") val languageCode: String?
)

interface AssemblyAiApi {
    @POST("v2/upload")
    suspend fun uploadFile(
        @Header("Authorization") apiKey: String,
        @Body file: RequestBody
    ): UploadResponse

    @POST("v2/transcript")
    suspend fun createTranscript(
        @Header("Authorization") apiKey: String,
        @Body request: TranscriptRequest
    ): TranscriptResponse

    @GET("v2/transcript/{id}")
    suspend fun getTranscript(
        @Header("Authorization") apiKey: String,
        @Path("id") id: String
    ): TranscriptResponse

    @DELETE("v2/transcript/{id}")
    suspend fun deleteTranscript(
        @Header("Authorization") apiKey: String,
        @Path("id") id: String
    )
}

object RetrofitClient {
    private const val BASE_URL = "https://api.assemblyai.com/"

    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
        }

        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .addInterceptor(logging)
            .build()
    }

    val api: AssemblyAiApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AssemblyAiApi::class.java)
    }
}