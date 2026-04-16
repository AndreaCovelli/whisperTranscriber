package com.andreacovelli.whispertranscriber

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private suspend fun resetTranscriptionSettings() {
        apiKeyManager.saveProvider(TranscriptionProvider.ELEVEN_LABS)
        apiKeyManager.saveTranscriptionCustomGeminiModelName("")
        apiKeyManager.saveSummarizerProvider(TranscriptionProvider.GEMINI_FLASH)
        apiKeyManager.saveSummarizerCustomGeminiModelName("")
        apiKeyManager.saveBackgroundShareSummaryNotificationsEnabled(false)
        apiKeyManager.saveSpeakerDiarization(false)
    }
}
