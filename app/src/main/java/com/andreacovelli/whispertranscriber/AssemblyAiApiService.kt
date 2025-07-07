package com.andreacovelli.whispertranscriber

import okhttp3.MultipartBody
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
import java.time.Instant

// --- Data Classes for AssemblyAI's JSON responses ---

// The response from uploading the file
data class UploadResponse(
    val upload_url: String
)

// The request body to start the transcription
// FIXED: Removed the problematic 'audio_end_at' parameter.
data class TranscriptRequest(
    val audio_url: String,
    val language_detection: Boolean = true
)

// The response for the transcript object, used for polling
data class TranscriptResponse(
    val id: String,
    val status: String,
    val text: String?, // Nullable because it's not present until completed
    val error: String?,  // Nullable for the same reason
    val language_code: String?
)


// --- Retrofit API Interface ---

interface AssemblyAiApi {
    // 1. First, we upload the local file and get a URL for it
    @POST("v2/upload")
    suspend fun uploadFile(
        @Header("Authorization") apiKey: String,
        @Body file: RequestBody
    ): UploadResponse

    // 2. Then, we ask AssemblyAI to transcribe the file at that URL
    @POST("v2/transcript")
    suspend fun createTranscript(
        @Header("Authorization") apiKey: String,
        @Body request: TranscriptRequest
    ): TranscriptResponse

    // 3. Finally, we poll this endpoint with the transcript ID to get the result
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


// --- Retrofit Client Object ---

object RetrofitClient {
    // Note the new baseUrl
    private const val BASE_URL = "https://api.assemblyai.com/"

    fun create(): AssemblyAiApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AssemblyAiApi::class.java)
    }
}