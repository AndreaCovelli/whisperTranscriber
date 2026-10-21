package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.transcription.TranscriptionMimeSupport
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
        assertFalse("audio/mp4" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        assertFalse("audio/x-m4a" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        assertFalse("audio/m4a" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
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

    @Test
    fun resolveUploadMimeType_replacesMpegLabelOnM4aContent() {
        // Header of a Pixel Recorder M4A that Google Drive reports as audio/mpeg.
        val m4aHeader = byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray(Charsets.US_ASCII)

        assertEquals("audio/mp4", TranscriptionMimeSupport.resolveUploadMimeType("audio/mpeg", m4aHeader))
    }

    @Test
    fun resolveUploadMimeType_keepsDeclaredAliasOfDetectedContainer() {
        val wavHeader = "RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.US_ASCII)

        assertEquals("audio/x-wav", TranscriptionMimeSupport.resolveUploadMimeType("audio/x-wav", wavHeader))
    }

    @Test
    fun resolveUploadMimeType_detectsContainerWhenTypeIsUnknown() {
        val flacHeader = "fLaC".toByteArray(Charsets.US_ASCII) + ByteArray(8)

        assertEquals("audio/flac", TranscriptionMimeSupport.resolveUploadMimeType(null, flacHeader))
    }

    @Test
    fun resolveUploadMimeType_keepsDeclaredTypeWithoutRecognizedSignature() {
        val id3Header = "ID3".toByteArray(Charsets.US_ASCII) + ByteArray(9)

        assertEquals("audio/mpeg", TranscriptionMimeSupport.resolveUploadMimeType("audio/mpeg", id3Header))
        assertEquals("audio/mpeg", TranscriptionMimeSupport.resolveUploadMimeType("audio/mpeg", ByteArray(0)))
    }
}
