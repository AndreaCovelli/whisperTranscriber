package com.andreacovelli.whispertranscriber.transcription

import android.media.MediaExtractor
import android.media.MediaFormat
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.requireUserFacing
import java.io.File

data class AudioInspection(val sizeBytes: Long, val durationUs: Long)

internal const val MICROSOFT_SPEECH_MAX_UPLOAD_BYTES = 250_000_000L
internal const val MICROSOFT_SPEECH_MAX_DURATION_US = 2L * 60L * 60L * 1_000_000L
internal const val GEMINI_MAX_AUDIO_DURATION_US = (9L * 60L + 30L) * 60L * 1_000_000L
internal const val GEMINI_CONVERSION_DURATION_LIMIT_US_EXCLUSIVE = GEMINI_MAX_AUDIO_DURATION_US + 1L

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
        val durationUs = (0 until extractor.trackCount)
            .asSequence()
            .map(extractor::getTrackFormat)
            .firstOrNull { format -> format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?.takeIf { format -> format.containsKey(MediaFormat.KEY_DURATION) }
            ?.getLong(MediaFormat.KEY_DURATION)
            ?: -1L
        AudioInspection(sizeBytes = file.length(), durationUs = durationUs)
    } finally {
        extractor.release()
    }
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
