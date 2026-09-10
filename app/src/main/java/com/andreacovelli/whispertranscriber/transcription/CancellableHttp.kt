package com.andreacovelli.whispertranscriber.transcription

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Consume and close the response before handing a value across the cancellation boundary. */
internal suspend fun <T> Call.awaitResult(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        @Suppress("TooGenericExceptionCaught") // Response decoders can fail; always close the response.
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use(read)
                continuation.resume(value)
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    })
}
