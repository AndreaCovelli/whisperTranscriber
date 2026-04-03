package com.andreacovelli.whispertranscriber.security

import com.google.genai.Client

internal inline fun <T> CharArray.useAsString(block: (String) -> T): T = block(String(this))

internal inline fun <T> CharArray.useGenAiClient(block: (Client) -> T): T = useAsString { apiKeyString ->
    block(Client.builder().apiKey(apiKeyString).build())
}
