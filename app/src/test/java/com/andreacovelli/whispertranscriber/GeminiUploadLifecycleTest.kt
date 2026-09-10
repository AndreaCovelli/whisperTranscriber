package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.security.DefaultGenAiClientFactory
import com.andreacovelli.whispertranscriber.transcription.GeminiTranscriber
import com.andreacovelli.whispertranscriber.transcription.ProviderResponseException
import com.andreacovelli.whispertranscriber.transcription.TranscriptionResult
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiUploadLifecycleTest {
    @Test
    fun invalidGeneration_deletesRemoteUploadAndPreservesStagedInput() = runBlocking {
        val source = File.createTempFile("gemini-staged-", ".wav").apply { writeBytes(byteArrayOf(1)) }
        val transport = FileApiTransport()
        val key = "test-key".toCharArray()
        try {
            val results = transcriber(source, key, transport).transcribe().toList()
            val failure = results.filterIsInstance<TranscriptionResult.Error>().single()
            assertTrue(failure.exception.stackTraceToString(), failure.exception is ProviderResponseException)
            assertEquals("Invalid provider response", failure.exception.message)
            assertTrue(transport.deleted.isCompleted)
            assertTrue(source.exists())
            assertTrue(key.all { it == '\u0000' })
        } finally {
            source.delete()
        }
    }

    @Test
    fun cancellationAfterUpload_usesAnIndependentClientForRemoteDeletion() = runBlocking {
        val source = File.createTempFile("gemini-staged-", ".wav").apply { writeBytes(byteArrayOf(1)) }
        val transport = FileApiTransport(blockGeneration = true)
        val key = "test-key".toCharArray()
        try {
            val result = async { transcriber(source, key, transport).transcribe().toList() }
            withTimeout(5_000) { transport.generating.await() }
            result.cancel()
            transport.release.countDown()
            withTimeout(5_000) { result.join() }
            assertTrue(result.isCancelled)
            assertTrue(transport.deleted.isCompleted)
            assertTrue(source.exists())
            assertTrue(key.all { it == '\u0000' })
        } finally {
            transport.release.countDown()
            source.delete()
        }
    }

    private fun transcriber(source: File, key: CharArray, transport: FileApiTransport) = GeminiTranscriber(
        source,
        "audio/wav",
        key,
        "gemini-test",
        false,
        "Speaker",
        DefaultGenAiClientFactory(OkHttpClient.Builder().addInterceptor(transport).build()),
        Dispatchers.IO,
    )

    private class FileApiTransport(private val blockGeneration: Boolean = false) : Interceptor {
        val generating = CompletableDeferred<Unit>()
        val deleted = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            val body = when {
                request.header("X-Goog-Upload-Command") == "start" -> {
                    response.header("X-Goog-Upload-URL", "https://upload.example.test/session")
                    "{}"
                }
                request.url.encodedPath == "/session" -> {
                    response.header("X-Goog-Upload-Status", "final")
                    """
                        {"file":{"name":"files/test","state":"ACTIVE","mimeType":"audio/wav",
                        "uri":"https://generativelanguage.googleapis.com/v1beta/files/test"}}
                    """.trimIndent()
                }
                request.method == "DELETE" -> {
                    deleted.complete(Unit)
                    "{}"
                }
                request.url.encodedPath.contains(":generateContent") -> {
                    generating.complete(Unit)
                    if (blockGeneration) check(release.await(10, TimeUnit.SECONDS))
                    "{}"
                }
                else -> error("Unexpected test request: ${request.method} ${request.url.encodedPath}")
            }
            return response.body(body.toResponseBody()).build()
        }
    }
}
