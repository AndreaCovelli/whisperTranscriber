package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.transcription.MICROSOFT_MAI_TRANSCRIBE_MODEL
import com.andreacovelli.whispertranscriber.transcription.MICROSOFT_SPEECH_API_VERSION
import com.andreacovelli.whispertranscriber.transcription.buildGeminiSpeakerDiarizationPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionProvidersTest {
    @Test
    fun microsoftMaiPreset_usesMaiTranscribe2() {
        assertEquals("MAI-Transcribe-2", MICROSOFT_MAI_TRANSCRIBE_MODEL)
        assertEquals("2025-10-15", MICROSOFT_SPEECH_API_VERSION)
        assertEquals("MAI-Transcribe-2", TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE.modelName)
    }

    @Test
    fun buildGeminiSpeakerDiarizationPrompt_usesLocalizedSpeakerLabelPrefix() {
        val prompt = buildGeminiSpeakerDiarizationPrompt("Locutore")

        assertTrue(prompt.contains("Locutore A: Hello"))
        assertTrue(prompt.contains("Locutore B: Hi"))
    }

    @Test
    fun buildGeminiSpeakerDiarizationPrompt_fallsBackToSpeakerWhenPrefixBlank() {
        val prompt = buildGeminiSpeakerDiarizationPrompt("   ")

        assertTrue(prompt.contains("Speaker A: Hello"))
        assertTrue(prompt.contains("Speaker B: Hi"))
    }
}
