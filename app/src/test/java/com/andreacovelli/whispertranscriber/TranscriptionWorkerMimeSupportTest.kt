package com.andreacovelli.whispertranscriber

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionWorkerMimeSupportTest {
    @Test
    fun geminiNativeUploadMimeTypes_matchSupportedFormats() {
        assertEquals(true, "audio/mp3" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        assertEquals(true, "audio/mpeg" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        assertEquals(true, "audio/wav" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        assertEquals(true, "audio/x-wav" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        assertEquals(true, "audio/wave" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        assertEquals(true, "audio/aac" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        assertEquals(true, "audio/ogg" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        assertEquals(true, "audio/flac" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        assertEquals(false, "audio/webm" in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
    }

    @Test
    fun microsoftSpeechNativeUploadMimeTypes_matchCurrentMaiTranscribeFormats() {
        assertEquals(true, "audio/mp3" in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes)
        assertEquals(true, "audio/mpeg" in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes)
        assertEquals(true, "audio/wav" in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes)
        assertEquals(true, "audio/flac" in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes)
        assertEquals(false, "audio/aac" in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes)
        assertEquals(false, "audio/ogg" in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes)
        assertEquals(false, "audio/webm" in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes)
        assertEquals(false, "audio/m4a" in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes)
    }

    @Test
    fun microsoftSpeechWavReencodeMimeTypes_coverWavContainerAliasesOnly() {
        assertEquals(true, "audio/wav" in TranscriptionMimeSupport.microsoftSpeechWavReencodeMimeTypes)
        assertEquals(true, "audio/x-wav" in TranscriptionMimeSupport.microsoftSpeechWavReencodeMimeTypes)
        assertEquals(true, "audio/wave" in TranscriptionMimeSupport.microsoftSpeechWavReencodeMimeTypes)
        assertEquals(false, "audio/mp3" in TranscriptionMimeSupport.microsoftSpeechWavReencodeMimeTypes)
        assertEquals(false, "audio/flac" in TranscriptionMimeSupport.microsoftSpeechWavReencodeMimeTypes)
    }
}
