package com.andreacovelli.whispertranscriber.recording

import android.Manifest
import android.os.Build
import com.andreacovelli.whispertranscriber.queue.MAX_SHARED_FILE_BYTES
import com.andreacovelli.whispertranscriber.queue.requireAcceptedSharedAudio
import com.andreacovelli.whispertranscriber.ui.components.formatRecordingElapsed
import com.andreacovelli.whispertranscriber.ui.main.MainMsg
import com.andreacovelli.whispertranscriber.ui.main.MainReducer
import com.andreacovelli.whispertranscriber.ui.main.MainUiState
import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTest {
    @Test
    fun recordingFileName_isTimestampedAndFileSystemSafe() {
        // 2026-10-22T14:30:05Z
        val name = recordingFileName("Recording", 1_792_679_405_000L, ZoneOffset.UTC)

        assertEquals("Recording 2026-10-22 14-30-05.m4a", name)
        assertTrue(name.none { it in "/\\:*?\"<>|" })
    }

    @Test
    fun recordingFileName_isAcceptedByTheShareIntake() {
        val name = recordingFileName("Recording", 0L, ZoneOffset.UTC)

        requireAcceptedSharedAudio(RECORDING_MIME_TYPE, name, "content://media/external/audio/media/1")
    }

    @Test
    fun maxRecordingSize_staysWithinTheSharedFileLimitAndGeminiDuration() {
        val maxSeconds = MAX_RECORDING_BYTES * 8 / RECORDING_BIT_RATE
        val geminiLimitSeconds = 9.5 * 60 * 60

        assertTrue(MAX_RECORDING_BYTES < MAX_SHARED_FILE_BYTES)
        assertTrue(maxSeconds < geminiLimitSeconds)
    }

    @Test
    fun requiredPermissions_addStorageOnlyBeforeScopedStorage() {
        assertArrayEquals(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            requiredRecordingPermissions(Build.VERSION_CODES.P),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            requiredRecordingPermissions(Build.VERSION_CODES.Q),
        )
    }

    @Test
    fun formatRecordingElapsed_showsMinutesThenHours() {
        assertEquals("00:00", formatRecordingElapsed(-5L))
        assertEquals("00:59", formatRecordingElapsed(59_999L))
        assertEquals("01:05", formatRecordingElapsed(65_000L))
        assertEquals("59:59", formatRecordingElapsed(3_599_000L))
        assertEquals("1:00:00", formatRecordingElapsed(3_600_000L))
        assertEquals("8:05:09", formatRecordingElapsed(29_109_000L))
    }

    @Test
    fun reducer_tracksRecordingStateAndLeavesItForRecordingCommands() {
        val recording = RecordingState.Recording(startedAtElapsedMillis = 42L)

        val state = MainReducer.reduce(MainUiState(), MainMsg.RecordingStateChanged(recording))

        assertEquals(recording, state.recording)
        assertEquals(state, MainReducer.reduce(state, MainMsg.StopRecording))
        assertEquals(state, MainReducer.reduce(state, MainMsg.DiscardRecording))
        assertEquals(
            RecordingState.Idle,
            MainReducer.reduce(state, MainMsg.RecordingStateChanged(RecordingState.Idle)).recording,
        )
    }
}
