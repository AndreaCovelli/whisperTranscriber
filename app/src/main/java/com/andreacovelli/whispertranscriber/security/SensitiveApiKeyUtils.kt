package com.andreacovelli.whispertranscriber.security

import com.google.genai.Client

/**
 * The Gemini SDK still requires a [String] API key, so this helper creates a short-lived immutable copy
 * inside the callback. Keep that string strictly scoped to the callback and avoid storing it anywhere.
 */
internal inline fun <T> CharArray.useAsString(block: (String) -> T): T = block(String(this))

/**
 * Builds a short-lived Gemini [Client] from a [CharArray] API key. The transient [String] created for the
 * SDK must not escape this callback or be stored in longer-lived fields.
 */
internal inline fun <T> CharArray.useGenAiClient(block: (Client) -> T): T = useAsString { apiKeyString ->
    block(Client.builder().apiKey(apiKeyString).build())
}
