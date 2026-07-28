package com.andreacovelli.whispertranscriber.security

import com.google.genai.Client
import com.google.genai.types.HttpOptions
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

interface GenAiClientFactory {
    fun create(apiKey: String, timeoutMillis: Int? = null): Client
}

class DefaultGenAiClientFactory @Inject constructor() : GenAiClientFactory {
    override fun create(apiKey: String, timeoutMillis: Int?): Client {
        val builder = Client.builder().apiKey(apiKey)
        if (timeoutMillis != null) {
            val httpOptions = HttpOptions.builder()
                .timeout(timeoutMillis)
                .build()
            builder.httpOptions(httpOptions)
        }
        return builder.build()
    }
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

/**
 * Builds and closes a short-lived Gemini [Client] from a [CharArray] API key. The transient [String]
 * created for the SDK must not escape this callback or be stored in longer-lived fields.
 */
internal inline fun <T> CharArray.useGenAiClient(
    clientFactory: GenAiClientFactory,
    timeoutMillis: Int? = null,
    block: (Client) -> T,
): T = useAsString { apiKeyString ->
    val client = clientFactory.create(apiKeyString, timeoutMillis)
    useClosing(client) {
        block(client)
    }
}

/**
 * Builds, uses, and closes a short-lived Gemini [Client] on [context]. Use this overload when the
 * SDK lifecycle can perform blocking I/O.
 */
internal suspend inline fun <T> CharArray.useGenAiClient(
    clientFactory: GenAiClientFactory,
    context: CoroutineContext,
    timeoutMillis: Int? = null,
    crossinline block: suspend (Client) -> T,
): T = useAsString { apiKeyString ->
    useClosing(
        context = context,
        resourceFactory = { clientFactory.create(apiKeyString, timeoutMillis) },
        block = block,
    )
}
