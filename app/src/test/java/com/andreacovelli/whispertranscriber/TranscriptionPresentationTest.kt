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
        )

        assertEquals(
            "Speaker A: Hello there!\nSpeaker B: Hi",
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

    private fun testUiJob(id: Int, batchId: String, status: String, isRunning: Boolean = false) = TranscriptionJobState(
        id = id,
        displayName = "file-$id.wav",
        batchId = batchId,
        status = status,
        isRunning = isRunning,
    )
}
