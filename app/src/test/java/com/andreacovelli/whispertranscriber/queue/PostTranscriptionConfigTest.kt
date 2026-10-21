package com.andreacovelli.whispertranscriber.queue

import com.andreacovelli.whispertranscriber.background.PostTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.summary.SummaryStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class PostTranscriptionConfigTest {
    @Test
    fun workSpec_roundTripsTheSummaryStyleThroughTheJobRow() {
        val spec = PostTranscriptionWorkSpec(
            summarizeAfterTranscription = true,
            notifyResult = false,
            summarizationSettings = SummarizationSettings(
                provider = TranscriptionProvider.GEMINI_PRO,
                customModelName = "",
                style = SummaryStyle.CUSTOM,
                customPrompt = "Only action items for Anna",
            ),
        )
        val config = spec.toPersistedConfig()
        val job = TranscriptionJob(
            uri = "file:///a.wav",
            displayName = "a.wav",
            summarizeAfterTranscription = config.summarizeAfterTranscription,
            notifyResult = config.notifyResult,
            queuedSummarizerProviderName = config.queuedSummarizerProviderName,
            queuedSummarizerCustomModelName = config.queuedSummarizerCustomModelName,
            queuedSummaryStyle = config.queuedSummaryStyle,
            queuedSummaryCustomPrompt = config.queuedSummaryCustomPrompt,
        )

        assertEquals(spec, job.postTranscriptionWorkSpec())
    }

    @Test
    fun jobsQueuedBeforeSummaryStyles_useTheAutomaticStyle() {
        val job = TranscriptionJob(
            uri = "file:///a.wav",
            displayName = "a.wav",
            summarizeAfterTranscription = true,
            queuedSummarizerProviderName = "GEMINI_FLASH",
        )

        val settings = job.postTranscriptionWorkSpec()?.summarizationSettings

        assertEquals(SummaryStyle.AUTOMATIC, settings?.style)
        assertEquals("", settings?.customPrompt)
    }
}
