package com.andreacovelli.whispertranscriber

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.TranscriptionQueueSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiKeyManagerInstrumentedTest {
    private lateinit var apiKeyManager: ApiKeyManager

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        apiKeyManager = ApiKeyManager(context)
        resetTranscriptionSettings()
    }

    @After
    fun tearDown() = runBlocking {
        resetTranscriptionSettings()
    }

    @Test
    fun getTranscriptionQueueSettings_returnsSavedGeminiSelection() = runBlocking {
        apiKeyManager.saveProvider(TranscriptionProvider.GEMINI_FLASH)
        apiKeyManager.saveTranscriptionCustomGeminiModelName("gemini-2.5-flash-preview")
        apiKeyManager.saveSpeakerDiarization(true)

        assertEquals(
            TranscriptionQueueSettings(
                provider = TranscriptionProvider.GEMINI_FLASH,
                customModelName = "gemini-2.5-flash-preview",
                speakerDiarizationEnabled = true,
            ),
            apiKeyManager.getTranscriptionQueueSettings(),
        )
    }

    @Test
    fun getTranscriptionQueueSettings_keepsSpeakerDiarizationForMicrosoft() = runBlocking {
        apiKeyManager.saveProvider(TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE)
        apiKeyManager.saveSpeakerDiarization(true)

        assertEquals(
            TranscriptionQueueSettings(
                provider = TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
                customModelName = "",
                speakerDiarizationEnabled = true,
            ),
            apiKeyManager.getTranscriptionQueueSettings(),
        )
    }

    @Test
    fun getTranscriptionQueueSettings_returnsSavedCustomGeminiSelection() = runBlocking {
        apiKeyManager.saveProvider(TranscriptionProvider.GEMINI_CUSTOM)
        apiKeyManager.saveTranscriptionCustomGeminiModelName("gemini-custom-audio-model")
        apiKeyManager.saveSpeakerDiarization(false)

        assertEquals(
            TranscriptionQueueSettings(
                provider = TranscriptionProvider.GEMINI_CUSTOM,
                customModelName = "gemini-custom-audio-model",
                speakerDiarizationEnabled = false,
            ),
            apiKeyManager.getTranscriptionQueueSettings(),
        )
    }

    @Test
    fun getSummarizationSettings_returnsSavedCustomGeminiSelection() = runBlocking {
        apiKeyManager.saveSummarizerProvider(TranscriptionProvider.GEMINI_CUSTOM)
        apiKeyManager.saveSummarizerCustomGeminiModelName("gemini-custom-summary-model")

        assertEquals(
            SummarizationSettings(
                provider = TranscriptionProvider.GEMINI_CUSTOM,
                customModelName = "gemini-custom-summary-model",
            ),
            apiKeyManager.getSummarizationSettings(),
        )
    }

    @Test
    fun backgroundShareSummaryNotificationsEnabledFlow_returnsSavedValue() = runBlocking {
        apiKeyManager.saveBackgroundShareSummaryNotificationsEnabled(true)

        assertEquals(
            true,
            apiKeyManager.backgroundShareSummaryNotificationsEnabledFlow.first(),
        )
    }

    @Test
    fun microsoftSpeechRegionFlow_returnsSavedNormalizedValue() = runBlocking {
        apiKeyManager.saveMicrosoftSpeechRegion(" WestUS ")

        assertEquals("westus", apiKeyManager.microsoftSpeechRegionFlow.first())
    }

    @Test
    fun microsoftSpeechRegionFlow_returnsNormalizedCustomEndpoint() = runBlocking {
        apiKeyManager.saveMicrosoftSpeechRegion(
            " https://My-Speech-Resource.cognitiveservices.azure.com/ ",
        )

        assertEquals(
            "https://my-speech-resource.cognitiveservices.azure.com",
            apiKeyManager.microsoftSpeechRegionFlow.first(),
        )
    }

    private suspend fun resetTranscriptionSettings() {
        apiKeyManager.saveProvider(TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE)
        apiKeyManager.saveMicrosoftSpeechRegion("")
        apiKeyManager.saveTranscriptionCustomGeminiModelName("")
        apiKeyManager.saveSummarizerProvider(TranscriptionProvider.GEMINI_FLASH)
        apiKeyManager.saveSummarizerCustomGeminiModelName("")
        apiKeyManager.saveBackgroundShareSummaryNotificationsEnabled(false)
        apiKeyManager.saveSpeakerDiarization(false)
    }
}
