package com.andreacovelli.whispertranscriber.recording

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.queue.MAX_SHARED_FILE_BYTES
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val RECORDING_MIME_TYPE = "audio/mp4"
internal const val RECORDING_FILE_EXTENSION = "m4a"
internal const val RECORDING_SAMPLE_RATE_HZ = 44_100
internal const val RECORDING_BIT_RATE = 64_000

// MediaRecorder can write slightly past its size cap while it finalizes the container, so keep headroom
// below the shared-audio limit. At 64 kbit/s this is about 8.5 hours, inside Gemini's 9.5-hour limit.
internal const val MAX_RECORDING_BYTES = MAX_SHARED_FILE_BYTES - 5_000_000L

sealed interface RecordingState {
    data object Idle : RecordingState

    /** The foreground service is starting; the microphone is not open yet. */
    data object Starting : RecordingState

    /** [startedAtElapsedMillis] comes from [android.os.SystemClock.elapsedRealtime]. */
    data class Recording(val startedAtElapsedMillis: Long) : RecordingState

    /** The recorder is finishing the file and copying it to shared storage. */
    data object Saving : RecordingState
}

sealed interface RecordingOutcome {
    /** [uri] points at the copy in shared storage; [savedMessage] names the folder it was saved to. */
    data class Saved(val uri: Uri, @param:StringRes val savedMessage: Int) : RecordingOutcome

    data class Failed(val message: UserMessage) : RecordingOutcome
}

private val recordingTimestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss", Locale.ROOT)

/** File-system-safe display name such as "Recording 2026-10-22 14-30-05.m4a". */
internal fun recordingFileName(label: String, startedAtMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val timestamp = recordingTimestampFormat.format(Instant.ofEpochMilli(startedAtMillis).atZone(zone))
    return "$label $timestamp.$RECORDING_FILE_EXTENSION"
}

/** Shared storage needs a write permission only before scoped storage (Android 10). */
internal fun requiredRecordingPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
    if (sdkInt < Build.VERSION_CODES.Q) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }
