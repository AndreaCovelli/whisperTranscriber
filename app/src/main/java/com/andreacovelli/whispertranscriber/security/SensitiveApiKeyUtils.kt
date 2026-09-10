package com.andreacovelli.whispertranscriber.security

import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

interface GenAiClientFactory {
    fun create(apiKey: String, timeoutMillis: Int? = null): GenAiOperation
}

class DefaultGenAiClientFactory @Inject constructor(private val httpClient: okhttp3.OkHttpClient) : GenAiClientFactory {
    override fun create(apiKey: String, timeoutMillis: Int?): GenAiOperation =
        GenAiOperation(apiKey, httpClient, timeoutMillis ?: 600_000)
}

/**
 * The Gemini SDK still requires a [String] API key, so this helper creates a short-lived immutable copy
 * inside the callback. Keep that string strictly scoped to the callback and avoid storing it anywhere.
 */
internal inline fun <T> CharArray.useAsString(block: (String) -> T): T = block(String(this))

internal inline fun <T> useClosing(resource: AutoCloseable, block: () -> T): T = try {
    block()
} finally {
    resource.close()
}

internal suspend inline fun <R : AutoCloseable, T> useClosing(
    context: CoroutineContext,
    crossinline resourceFactory: () -> R,
    crossinline block: suspend (R) -> T,
): T = withContext(context) {
    val resource = resourceFactory()
    try {
        block(resource)
    } finally {
        resource.close()
    }
}

/** Each operation owns its transport and cancellation; API key strings remain scoped to this call. */
internal suspend inline fun <T> CharArray.useGenAiClient(
    clientFactory: GenAiClientFactory,
    context: CoroutineContext,
    timeoutMillis: Int? = null,
    crossinline block: suspend (GenAiOperation) -> T,
): T = useAsString { apiKeyString ->
    useClosing(context, { clientFactory.create(apiKeyString, timeoutMillis) }, block)
}
