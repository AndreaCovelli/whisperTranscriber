package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.security.GenAiOperation
import com.andreacovelli.whispertranscriber.security.hasStructuredRetryGuidance
import com.andreacovelli.whispertranscriber.transcription.awaitResult
import com.andreacovelli.whispertranscriber.transcription.shouldRetryGeminiApiError
import com.google.genai.errors.ApiException
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCancellationTest {
    @Test
    fun cancelledHttpRequest_closesTheSocket() = runBlocking {
        HangingServer().use { server ->
            val request = async {
                OkHttpClient().newCall(Request.Builder().url(server.url).build()).awaitResult { it.body.string() }
            }
            withTimeout(5_000) { server.started.await() }
            request.cancelAndJoin()
            withTimeout(5_000) { server.disconnected.await() }
        }
    }

    @Test
    fun cancelledGeminiGeneration_doesNotCancelAnotherOperation() = runBlocking {
        HangingServer().use { server ->
            val baseClient = OkHttpClient()
            val generation = async(Dispatchers.IO) {
                GenAiOperation("test-key", baseClient, 30_000, server.url).use { operation ->
                    operation.call { it.models.generateContent("slow", "test", null) }
                }
            }
            withTimeout(5_000) { server.started.await() }
            val model = async(Dispatchers.IO) {
                GenAiOperation("test-key", baseClient, 5_000, server.url).use { operation ->
                    operation.call { it.models.get("fast", null) }
                }
            }
            withTimeout(5_000) { server.fastStarted.await() }
            withTimeout(5_000) { generation.cancelAndJoin() }
            withTimeout(5_000) { server.disconnected.await() }
            server.releaseFast.countDown()
            assertEquals("models/fast", withTimeout(5_000) { model.await() }.name().get())
        }
    }

    @Test
    fun cancelledGeminiUpload_stopsLocalFileTransfer() = runBlocking {
        val file = File.createTempFile("cancel-upload-", ".wav").apply { writeBytes(ByteArray(2 * 1024 * 1024)) }
        try {
            HangingServer().use { server ->
                val upload = async(Dispatchers.IO) {
                    GenAiOperation("test-key", OkHttpClient(), 30_000, server.url).use { operation ->
                        operation.call { it.files.upload(file.absolutePath, null) }
                    }
                }
                withTimeout(5_000) { server.started.await() }
                withTimeout(5_000) { upload.cancelAndJoin() }
                withTimeout(5_000) { server.disconnected.await() }
                assertTrue(upload.isCancelled)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun sdkPreservesStructuredRetryGuidanceWhenItsErrorParserDropsDetails() = runBlocking {
        val payload = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"daily quota",
            "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"3s"}]}}
        """.trimIndent()
        assertTrue(hasStructuredRetryGuidance(payload))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(429).message("Too many requests")
                .body(okhttp3.ResponseBody.Companion.run { payload.toResponseBody() }).build()
        }.build()
        GenAiOperation("test-key", client, 5_000).use { operation ->
            try {
                operation.call { it.models.get("fast", null) }
                error("Expected rate limit")
            } catch (error: ApiException) {
                assertTrue(shouldRetryGeminiApiError(error))
            }
        }
    }

    /** Accepts real sockets so cancellation must actually interrupt OkHttp I/O. */
    private class HangingServer : AutoCloseable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        val started = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val fastStarted = CompletableDeferred<Unit>()
        val releaseFast = CountDownLatch(1)
        val url = "http://127.0.0.1:${server.localPort}/"

        init {
            executor.execute {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: java.io.IOException) {
                        break
                    }
                    sockets.add(socket)
                    executor.execute { handle(socket) }
                }
            }
        }

        private fun handle(socket: Socket) = socket.use {
            socket.soTimeout = 5_000
            val input = socket.getInputStream().buffered()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val value = input.read()
                if (value < 0) return@use
                header.append(value.toChar())
            }
            if (header.contains("models/fast")) {
                fastStarted.complete(Unit)
                check(releaseFast.await(5, TimeUnit.SECONDS))
                val body = """{"name":"models/fast"}"""
                socket.getOutputStream().write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        ).toByteArray(),
                )
            } else {
                started.complete(Unit)
                try {
                    while (input.read() >= 0) { /* Consume until the cancelled client closes its socket. */ }
                    disconnected.complete(Unit)
                } catch (error: java.io.IOException) {
                    disconnected.completeExceptionally(error)
                }
            }
        }

        override fun close() {
            releaseFast.countDown()
            server.close()
            synchronized(sockets) { sockets.forEach { it.close() } }
            executor.shutdownNow()
        }
    }
}
