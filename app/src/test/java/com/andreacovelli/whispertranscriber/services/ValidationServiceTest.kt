package com.andreacovelli.whispertranscriber.services

import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import java.net.UnknownHostException
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationServiceTest {

    private fun createValidationService(interceptor: (okhttp3.Interceptor.Chain) -> Response): ValidationService {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> interceptor(chain) }
            .build()

        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val settings = unsafe.allocateInstance(ApiKeyManager::class.java) as ApiKeyManager

        return ValidationService(
            settings = settings,
            okHttpClient = client,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun validateMicrosoftSpeechRegion_validLiveRegion_returnsValid() = runBlocking {
        val service = createValidationService { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = service.validateMicrosoftSpeechRegion("swedencentral")
        assertTrue(result.isValid)
        assertEquals("swedencentral", result.normalizedRegion)
    }

    @Test
    fun validateMicrosoftSpeechRegion_unresolvableHost_returnsErrorMessage() = runBlocking {
        val service = createValidationService { chain ->
            val host = chain.request().url.host
            throw UnknownHostException("Unable to resolve host \"$host\"")
        }

        val result = service.validateMicrosoftSpeechRegion("dydy")
        assertFalse(result.isValid)
        assertTrue(
            result.errorMessage?.contains("Could not reach Azure Speech for region \"dydy\"") == true,
        )
    }

    @Test
    fun validateMicrosoftSpeechRegion_invalidFormat_returnsFormatErrorMessageWithoutNetworkCall() = runBlocking {
        var networkCalled = false
        val service = createValidationService { chain ->
            networkCalled = true
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = service.validateMicrosoftSpeechRegion("INVALID_REGION!")
        assertFalse(result.isValid)
        assertFalse(networkCalled)
        assertTrue(result.errorMessage?.contains("lowercase Azure Speech region") == true)
    }

    @Test
    fun validateMicrosoftSpeechRegion_customEndpoint_probesTheConfiguredHost() = runBlocking {
        val requestedUrls = Collections.synchronizedList(mutableListOf<String>())
        val service = createValidationService { chain ->
            requestedUrls += chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = service.validateMicrosoftSpeechRegion(
            "https://my-speech-resource.cognitiveservices.azure.com",
        )

        assertTrue(result.isValid)
        assertEquals(
            "https://my-speech-resource.cognitiveservices.azure.com",
            result.normalizedRegion,
        )
        assertEquals(2, requestedUrls.size)
        assertTrue(
            requestedUrls.all {
                it.startsWith(
                    "https://my-speech-resource.cognitiveservices.azure.com/" +
                        "speechtotext/transcriptions/locales?api-version=",
                )
            },
        )
    }

    @Test
    fun validateMicrosoftSpeechRegion_customEndpointWithMissingRoutes_returnsError() = runBlocking {
        val service = createValidationService { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = service.validateMicrosoftSpeechRegion(
            "https://not-speech.cognitiveservices.azure.com",
        )

        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("HTTP 404") == true)
    }

    @Test
    fun validateMicrosoftSpeechKey_validKey_returnsValid() = runBlocking {
        val service = createValidationService { chain ->
            assertEquals("valid-test-key", chain.request().header("Ocp-Apim-Subscription-Key"))
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = service.validateMicrosoftSpeechKey("valid-test-key", "swedencentral")
        assertTrue(result.isValid)
    }

    @Test
    fun validateMicrosoftSpeechKey_customEndpoint_sendsKeyOnlyToConfiguredHost() = runBlocking {
        val service = createValidationService { chain ->
            assertEquals("custom-test-key", chain.request().header("Ocp-Apim-Subscription-Key"))
            assertEquals("private-speech.cognitiveservices.azure.com", chain.request().url.host)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = service.validateMicrosoftSpeechKey(
            "custom-test-key",
            "https://private-speech.cognitiveservices.azure.com",
        )

        assertTrue(result.isValid)
    }

    @Test
    fun validateMicrosoftSpeechKey_invalidKey_returnsDescriptiveError() = runBlocking {
        val service = createValidationService { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = service.validateMicrosoftSpeechKey("wrong-key", "swedencentral")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("Use the Speech resource key from your Foundry resource") == true)
    }
}
