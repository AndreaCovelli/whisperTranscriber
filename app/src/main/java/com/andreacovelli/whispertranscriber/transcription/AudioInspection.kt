package com.andreacovelli.whispertranscriber.transcription

import android.media.MediaExtractor
import android.media.MediaFormat
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.media.firstAudioTrack
import com.andreacovelli.whispertranscriber.requireUserFacing
import java.io.File

data class AudioInspection(val sizeBytes: Long, val durationUs: Long)

internal const val MICROSOFT_SPEECH_MAX_UPLOAD_BYTES = 250_000_000L
internal const val MICROSOFT_SPEECH_MAX_DURATION_US = 2L * 60L * 60L * 1_000_000L
internal const val GEMINI_MAX_AUDIO_DURATION_US = (9L * 60L + 30L) * 60L * 1_000_000L
internal const val GEMINI_CONVERSION_DURATION_LIMIT_US_EXCLUSIVE = GEMINI_MAX_AUDIO_DURATION_US + 1L

private const val MICROSECONDS_PER_MINUTE = 60L * 1_000_000L

/** Longer recordings are split into parts, so the overall cap no longer depends on one provider request. */
internal const val CHUNKED_AUDIO_DURATION_LIMIT_US_EXCLUSIVE = GEMINI_CONVERSION_DURATION_LIMIT_US_EXCLUSIVE

/** How far a cut may move from its ideal position to land on a pause. */
internal const val CHUNK_SEARCH_WINDOW_US = 2L * MICROSECONDS_PER_MINUTE

/**
 * Recordings of at least [thresholdUs] are transcribed in parts of at most [maxChunkDurationUs]. Microsoft
 * rejects two hours or more; Gemini can accept longer audio, but long requests risk truncated output and
 * skipped passages, so it is split after an hour.
 */
internal enum class AudioChunkingPolicy(val thresholdUs: Long, val maxChunkDurationUs: Long) {
    MICROSOFT_SPEECH(MICROSOFT_SPEECH_MAX_DURATION_US, 110L * MICROSECONDS_PER_MINUTE),
    GEMINI(60L * MICROSECONDS_PER_MINUTE, 60L * MICROSECONDS_PER_MINUTE),
    ;

    fun requiresChunking(durationUs: Long): Boolean = durationUs >= thresholdUs

    companion object {
        fun forProvider(usingMicrosoftSpeech: Boolean): AudioChunkingPolicy =
            if (usingMicrosoftSpeech) MICROSOFT_SPEECH else GEMINI
    }
}

internal fun validateMicrosoftSpeechAudio(inspection: AudioInspection) {
    requireUserFacing(inspection.sizeBytes in 1 until MICROSOFT_SPEECH_MAX_UPLOAD_BYTES) {
        UserMessage(R.string.microsoft_speech_audio_too_large)
    }
    requireUserFacing(inspection.durationUs in 1 until MICROSOFT_SPEECH_MAX_DURATION_US) {
        UserMessage(R.string.microsoft_speech_audio_too_long)
    }
}

internal fun isMicrosoftSpeechUploadSizeSupported(sizeBytes: Long): Boolean =
    sizeBytes in 1 until MICROSOFT_SPEECH_MAX_UPLOAD_BYTES

internal fun inspectAudioFile(file: File): AudioInspection {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.absolutePath)
        val durationUs = extractor.firstAudioTrack()?.value
            ?.takeIf { format -> format.containsKey(MediaFormat.KEY_DURATION) }
            ?.getLong(MediaFormat.KEY_DURATION)
            ?: -1L
        AudioInspection(sizeBytes = file.length(), durationUs = durationUs)
    } finally {
        extractor.release()
    }
}

/** Returns the audio duration, or -1 when the file cannot be inspected or does not report one. */
internal fun inspectDurationUsOrUnknown(file: File): Long = try {
    inspectAudioFile(file).durationUs
} catch (_: java.io.IOException) {
    -1L
} catch (_: IllegalArgumentException) {
    -1L
}

internal fun validateMeasurableGeminiAudio(file: File) {
    val inspection = try {
        inspectAudioFile(file)
    } catch (_: java.io.IOException) {
        return
    } catch (_: IllegalArgumentException) {
        return
    }
    validateGeminiAudioDuration(inspection.durationUs)
}

internal fun validateGeminiAudioDuration(durationUs: Long) {
    if (durationUs > GEMINI_MAX_AUDIO_DURATION_US) throw ProviderResponseException(R.string.gemini_audio_too_long)
}
