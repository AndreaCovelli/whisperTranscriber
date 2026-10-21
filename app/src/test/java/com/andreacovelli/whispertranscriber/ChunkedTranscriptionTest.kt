package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.transcription.AudioChunkingPolicy
import com.andreacovelli.whispertranscriber.transcription.MICROSOFT_SPEECH_MAX_DURATION_US
import com.andreacovelli.whispertranscriber.transcription.TranscriptionData
import com.andreacovelli.whispertranscriber.transcription.combinePartTranscriptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedTranscriptionTest {
    private val minuteUs = 60L * 1_000_000L

    @Test
    fun microsoftPolicy_splitsOnlyRecordingsMicrosoftRejects() {
        val policy = AudioChunkingPolicy.forProvider(usingMicrosoftSpeech = true)

        assertFalse(policy.requiresChunking(MICROSOFT_SPEECH_MAX_DURATION_US - 1))
        assertTrue(policy.requiresChunking(MICROSOFT_SPEECH_MAX_DURATION_US))
        assertTrue(policy.maxChunkDurationUs < MICROSOFT_SPEECH_MAX_DURATION_US)
    }

    @Test
    fun geminiPolicy_splitsRecordingsOfAnHourOrMore() {
        val policy = AudioChunkingPolicy.forProvider(usingMicrosoftSpeech = false)

        assertFalse(policy.requiresChunking(59 * minuteUs))
        assertTrue(policy.requiresChunking(60 * minuteUs))
        assertFalse(policy.requiresChunking(-1))
    }

    @Test
    fun combinePartTranscriptions_joinsPartsInOrderWithoutMarkers() {
        val combined = combinePartTranscriptions(
            parts = listOf(
                TranscriptionData(" First part. ", "it-IT", "model-a"),
                TranscriptionData("", "und", "model-b"),
                TranscriptionData("Third part.", "it-IT", "model-b"),
            ),
            partMarker = null,
        )

        assertEquals("First part.\nThird part.", combined.text)
        assertEquals("it-IT", combined.language)
        assertEquals("model-a", combined.model)
    }

    @Test
    fun combinePartTranscriptions_marksPartsWhenSpeakerLabelsRestart() {
        val combined = combinePartTranscriptions(
            parts = listOf(
                TranscriptionData("Speaker A: Hi", "en-US", "model"),
                TranscriptionData("Speaker A: Bye", "en-US", "model"),
            ),
            partMarker = { number -> "[Part $number of 2]" },
        )

        assertEquals("[Part 1 of 2]\nSpeaker A: Hi\n\n[Part 2 of 2]\nSpeaker A: Bye", combined.text)
    }

    @Test
    fun combinePartTranscriptions_skipsMarkersForEmptyParts() {
        val marker = { number: Int -> "[Part $number of 3]" }
        val silent = combinePartTranscriptions(
            parts = listOf(TranscriptionData(" ", "und", "model"), TranscriptionData("", "und", "model")),
            partMarker = marker,
        )
        val partlySilent = combinePartTranscriptions(
            parts = listOf(
                TranscriptionData("Speaker A: Hi", "en-US", "model"),
                TranscriptionData("", "und", "model"),
                TranscriptionData("Speaker A: Bye", "en-US", "model"),
            ),
            partMarker = marker,
        )

        assertEquals("", silent.text)
        assertEquals("[Part 1 of 3]\nSpeaker A: Hi\n\n[Part 3 of 3]\nSpeaker A: Bye", partlySilent.text)
    }

    @Test
    fun combinePartTranscriptions_usesMostCommonDetectedLanguage() {
        val combined = combinePartTranscriptions(
            parts = listOf(
                TranscriptionData("a", "und", "m"),
                TranscriptionData("b", "en-US", "m"),
                TranscriptionData("c", "it-IT", "m"),
                TranscriptionData("d", "it-IT", "m"),
            ),
            partMarker = null,
        )

        assertEquals("it-IT", combined.language)
    }
}
