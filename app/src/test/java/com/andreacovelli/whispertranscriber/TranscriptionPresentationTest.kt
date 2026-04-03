package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.ui.screens.BatchProgressState
import com.andreacovelli.whispertranscriber.ui.screens.buildBatchProgressState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionPresentationTest {
    @Test
    fun buildBatchProgressState_returnsNullForSingleJob() {
        val jobs = listOf(
            testUiJob(id = 1, batchId = "batch-a", status = "Queued"),
        )

        assertNull(buildBatchProgressState(jobs))
    }

    @Test
    fun buildBatchProgressState_usesLatestActiveMultiFileBatch() {
        val jobs = listOf(
            testUiJob(id = 1, batchId = "batch-old", status = "Completed"),
            testUiJob(id = 2, batchId = "batch-old", status = "Completed"),
            testUiJob(id = 3, batchId = "batch-new", status = "Completed"),
            testUiJob(id = 4, batchId = "batch-new", status = "Transcribing", isRunning = true),
            testUiJob(id = 5, batchId = "batch-new", status = "Queued"),
        )

        assertEquals(
            BatchProgressState(currentFileNumber = 2, totalFiles = 3, completedFiles = 1),
            buildBatchProgressState(jobs),
        )
    }

    @Test
    fun formatElevenLabsSpeakerTranscript_groupsContiguousWordsBySpeaker() {
        val transcript = formatElevenLabsSpeakerTranscript(
            listOf(
                ElevenLabsWord(text = "Hello", start = 0.0, end = 0.4, type = "word", speakerId = "speaker_1"),
                ElevenLabsWord(text = "there", start = 0.5, end = 0.8, type = "word", speakerId = "speaker_1"),
                ElevenLabsWord(text = "!", start = 0.8, end = 0.9, type = "punctuation", speakerId = "speaker_1"),
                ElevenLabsWord(text = "Hi", start = 1.0, end = 1.2, type = "word", speakerId = "speaker_2"),
            ),
            languageCode = "en-US",
            speakerLabelPrefix = "Speaker",
        )

        assertEquals(
            "Speaker A: Hello there!\nSpeaker B: Hi",
            transcript,
        )
    }

    @Test
    fun formatElevenLabsSpeakerTranscript_preservesOpeningPunctuationSpacingWithinSpeakerSection() {
        val transcript = formatElevenLabsSpeakerTranscript(
            listOf(
                ElevenLabsWord(text = "Hello", start = 0.0, end = 0.4, type = "word", speakerId = "speaker_1"),
                ElevenLabsWord(text = "(", start = 0.4, end = 0.5, type = "punctuation", speakerId = "speaker_1"),
                ElevenLabsWord(text = "world", start = 0.5, end = 0.8, type = "word", speakerId = "speaker_1"),
                ElevenLabsWord(text = ")", start = 0.8, end = 0.9, type = "punctuation", speakerId = "speaker_1"),
            ),
            languageCode = "en-US",
            speakerLabelPrefix = "Speaker",
        )

        assertEquals("Speaker A: Hello (world)", transcript)
    }

    @Test
    fun formatElevenLabsSpeakerTranscript_doesNotMergeUnlabeledPrefixWithFirstNamedSpeaker() {
        val transcript = formatElevenLabsSpeakerTranscript(
            listOf(
                ElevenLabsWord(text = "Intro", start = 0.0, end = 0.4, type = "word", speakerId = null),
                ElevenLabsWord(text = "Hello", start = 0.5, end = 0.8, type = "word", speakerId = "speaker_1"),
                ElevenLabsWord(text = "Hi", start = 0.9, end = 1.2, type = "word", speakerId = "speaker_2"),
            ),
            languageCode = "en-US",
            speakerLabelPrefix = "Speaker",
        )

        assertEquals(
            "Speaker A: Intro\nSpeaker B: Hello\nSpeaker C: Hi",
            transcript,
        )
    }

    @Test
    fun formatElevenLabsSpeakerTranscript_returnsBlankWithoutSpeakerIds() {
        val transcript = formatElevenLabsSpeakerTranscript(
            listOf(
                ElevenLabsWord(text = "Hello", start = 0.0, end = 0.4, type = "word", speakerId = null),
            ),
        )

        assertEquals("", transcript)
    }

    @Test
    fun formatElevenLabsSpeakerTranscript_usesLocalizedSpeakerPrefix() {
        val transcript = formatElevenLabsSpeakerTranscript(
            listOf(
                ElevenLabsWord(text = "Ciao", start = 0.0, end = 0.4, type = "word", speakerId = "speaker_1"),
            ),
            languageCode = "it-IT",
            speakerLabelPrefix = "Voce",
        )

        assertEquals("Voce A: Ciao", transcript)
    }

    @Test
    fun formatElevenLabsSpeakerTranscript_avoidsWordSpacingForJapanese() {
        val transcript = formatElevenLabsSpeakerTranscript(
            listOf(
                ElevenLabsWord(text = "こんにちは", start = 0.0, end = 0.4, type = "word", speakerId = "speaker_1"),
                ElevenLabsWord(text = "世界", start = 0.5, end = 0.8, type = "word", speakerId = "speaker_1"),
                ElevenLabsWord(text = "。", start = 0.8, end = 0.9, type = "punctuation", speakerId = "speaker_1"),
            ),
            languageCode = "ja-JP",
            speakerLabelPrefix = "Speaker",
        )

        assertEquals("Speaker A: こんにちは世界。", transcript)
    }

    private fun testUiJob(id: Int, batchId: String, status: String, isRunning: Boolean = false) = TranscriptionJobState(
        id = id,
        displayName = "file-$id.wav",
        batchId = batchId,
        status = status,
        isRunning = isRunning,
    )
}
