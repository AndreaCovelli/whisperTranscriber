package com.andreacovelli.whispertranscriber.security

import com.google.genai.Client
import com.google.genai.errors.ApiException
import com.google.genai.types.ClientOptions
import com.google.genai.types.HttpOptions
import com.google.genai.types.HttpRetryOptions
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject

internal class GeminiApiFailure(error: ApiException, val retrySuggested: Boolean) :
    ApiException(error.code(), error.status(), error.message())

internal fun hasStructuredRetryGuidance(body: String): Boolean = try {
    val details = JSONObject(body).optJSONObject("error")?.optJSONArray("details")
    (0 until (details?.length() ?: 0)).any { index ->
        val detail = details?.optJSONObject(index)
        detail?.optString("@type")?.endsWith("google.rpc.RetryInfo") == true && detail.has("retryDelay")
    }
} catch (_: JSONException) {
    false
}

/** The SDK uses synchronous I/O; cancellation closes only this operation's calls. */
class GenAiOperation(apiKey: String, baseClient: OkHttpClient, timeoutMillis: Int, baseUrl: String? = null) :
    AutoCloseable {
    private val cancelled = AtomicBoolean()
    private val retrySuggested = AtomicBoolean()
    private val transport = baseClient.newBuilder()
        .dispatcher(Dispatcher())
        .connectionPool(ConnectionPool())
        .callTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            if (cancelled.get()) throw InterruptedIOException("Operation cancelled")
            chain.proceed(chain.request()).also { response ->
                if (response.code == 429) {
                    retrySuggested.set(
                        response.header("Retry-After") != null ||
                            hasStructuredRetryGuidance(response.peekBody(64 * 1024).string()),
                    )
                }
            }
        }
        .build()
    private val client = Client.builder()
        .apiKey(apiKey)
        .clientOptions(ClientOptions.builder().customHttpClient(transport).build())
        .httpOptions(
            HttpOptions.builder().apply {
                timeout(timeoutMillis)
                retryOptions(HttpRetryOptions.builder().attempts(1).build())
                if (baseUrl != null) baseUrl(baseUrl)
            }.build(),
        )
        .build()

    // Called on the injected I/O dispatcher. Register before entering blocking SDK code.
    @Suppress("TooGenericExceptionCaught") // SDK transport and decoding exceptions must resume the caller.
    suspend fun <T> call(block: (Client) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        if (continuation.isActive) {
            try {
                continuation.resume(block(client))
            } catch (error: ApiException) {
                continuation.resumeWithException(GeminiApiFailure(error, retrySuggested.get()))
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        transport.dispatcher.cancelAll()
    }

    override fun close() {
        cancel()
        try {
            client.close()
        } finally {
            transport.connectionPool.evictAll()
            transport.dispatcher.executorService.shutdown()
        }
    }
}
