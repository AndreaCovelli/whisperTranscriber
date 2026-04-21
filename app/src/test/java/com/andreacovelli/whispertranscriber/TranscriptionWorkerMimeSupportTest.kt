package com.andreacovelli.whispertranscriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TranscriptionWorkerMimeSupportTest {
    @Test
    fun geminiNativeUploadMimeTypes_matchSupportedFormats() {
        assertEquals(
            setOf(
                "audio/wav",
                "audio/x-wav",
                "audio/wave",
                "audio/mp3",
                "audio/mpeg",
                "audio/aiff",
                "audio/x-aiff",
                "audio/aac",
                "audio/ogg",
                "audio/vorbis",
                "audio/flac",
                "audio/x-flac",
            ),
            TranscriptionMimeSupport.geminiNativeUploadMimeTypes,
        )
        assertFalse("audio/webm" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
    }

    @Test
    fun microsoftSpeechNativeUploadMimeTypes_matchCurrentMaiTranscribeFormats() {
        assertEquals(
            setOf(
                "audio/mp3",
                "audio/mpeg",
                "audio/wav",
                "audio/x-wav",
                "audio/wave",
                "audio/flac",
                "audio/x-flac",
            ),
            TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes,
        )
        assertFalse("audio/aac" in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes)
        assertFalse("audio/ogg" in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes)
        assertFalse("audio/webm" in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes)
        assertFalse("audio/m4a" in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes)
    }

    @Test
    fun microsoftSpeechWavReencodeMimeTypes_coverWavContainerAliasesOnly() {
        assertEquals(
            setOf(
                "audio/wav",
                "audio/x-wav",
                "audio/wave",
            ),
            TranscriptionMimeSupport.microsoftSpeechWavReencodeMimeTypes,
        )
        assertFalse("audio/mp3" in TranscriptionMimeSupport.microsoftSpeechWavReencodeMimeTypes)
        assertFalse("audio/flac" in TranscriptionMimeSupport.microsoftSpeechWavReencodeMimeTypes)
    }
}
