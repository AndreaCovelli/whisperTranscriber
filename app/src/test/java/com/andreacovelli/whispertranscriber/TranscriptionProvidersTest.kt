package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.transcription.buildGeminiSpeakerDiarizationPrompt
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionProvidersTest {
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
