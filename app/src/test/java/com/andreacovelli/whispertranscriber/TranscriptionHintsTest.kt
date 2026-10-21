package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.TranscriptionQueueSettings
import com.andreacovelli.whispertranscriber.transcription.MAX_VOCABULARY_TERMS
import com.andreacovelli.whispertranscriber.transcription.TRANSCRIPTION_LANGUAGE_CODES
import com.andreacovelli.whispertranscriber.transcription.TranscriptionHints
import com.andreacovelli.whispertranscriber.transcription.buildGeminiTranscriptionPrompt
import com.andreacovelli.whispertranscriber.transcription.buildMicrosoftSpeechDefinition
import com.andreacovelli.whispertranscriber.transcription.encodeVocabulary
import com.andreacovelli.whispertranscriber.transcription.normalizeTranscriptionLanguage
import com.andreacovelli.whispertranscriber.transcription.parseVocabulary
import com.andreacovelli.whispertranscriber.transcription.transcriptionHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionHintsTest {
    @Test
    fun parseVocabulary_splitsTrimsAndDropsDuplicates() {
        val terms = parseVocabulary(" Covelli, MAI-Transcribe \n\n covelli ,Kotlin\n")

        assertEquals(listOf("Covelli", "MAI-Transcribe", "Kotlin"), terms)
    }

    @Test
    fun parseVocabulary_limitsTermCount() {
        val terms = parseVocabulary((1..150).joinToString("\n") { "term$it" })

        assertEquals(MAX_VOCABULARY_TERMS, terms.size)
        assertEquals("term1", terms.first())
    }

    @Test
    fun normalizeTranscriptionLanguage_acceptsOnlySupportedCodes() {
        assertEquals("it", normalizeTranscriptionLanguage(" IT "))
        assertEquals("yue", normalizeTranscriptionLanguage("yue"))
        assertEquals("", normalizeTranscriptionLanguage("xx"))
        assertEquals("", normalizeTranscriptionLanguage(null))
        assertEquals(60, TRANSCRIPTION_LANGUAGE_CODES.toSet().size)
    }

    @Test
    fun queueSettings_produceNormalizedHints() {
        val settings = TranscriptionQueueSettings(
            provider = TranscriptionProvider.GEMINI_FLASH,
            customModelName = "",
            speakerDiarizationEnabled = false,
            languageHint = "de",
            vocabulary = "Zürich, ETH",
        )

        assertEquals(TranscriptionHints("de", listOf("Zürich", "ETH")), settings.hints())
    }

    @Test
    fun job_restoresHintsStoredWhenQueued() {
        val job = TranscriptionJob(
            uri = "file:///a.wav",
            displayName = "a.wav",
            languageHint = "fr",
            vocabulary = encodeVocabulary(listOf("Lyon", "SNCF")),
        )

        assertEquals(TranscriptionHints("fr", listOf("Lyon", "SNCF")), job.transcriptionHints())
    }

    @Test
    fun microsoftDefinition_omitsHintsByDefault() {
        val definition = buildMicrosoftSpeechDefinition("MAI-Transcribe-2", speakerDiarizationEnabled = false)

        assertFalse(definition.has("locales"))
        assertFalse(definition.has("phraseList"))
    }

    @Test
    fun microsoftDefinition_sendsLocaleAndPhraseList() {
        val definition = buildMicrosoftSpeechDefinition(
            "MAI-Transcribe-2",
            speakerDiarizationEnabled = true,
            hints = TranscriptionHints("it", listOf("Covelli", "Kotlin")),
        )

        assertEquals("it", definition.getJSONArray("locales").getString(0))
        assertEquals(1, definition.getJSONArray("locales").length())
        val phrases = definition.getJSONObject("phraseList").getJSONArray("phrases")
        assertEquals(listOf("Covelli", "Kotlin"), (0 until phrases.length()).map(phrases::getString))
        assertTrue(definition.getJSONObject("diarization").getBoolean("enabled"))
    }

    @Test
    fun geminiPrompt_addsLanguageAndVocabularyHints() {
        val prompt = buildGeminiTranscriptionPrompt(
            speakerDiarizationEnabled = false,
            speakerLabelPrefix = "Speaker",
            hints = TranscriptionHints("it", listOf("Covelli", "Kotlin")),
        )

        assertTrue(prompt.startsWith("Transcribe the provided audio."))
        assertTrue(prompt.contains("The speech is in Italian (it)."))
        assertTrue(prompt.contains("use this spelling: Covelli; Kotlin."))
    }

    @Test
    fun geminiPrompt_isUnchangedWithoutHints() {
        val prompt = buildGeminiTranscriptionPrompt(
            speakerDiarizationEnabled = true,
            speakerLabelPrefix = "Speaker",
            hints = TranscriptionHints(),
        )

        assertTrue(prompt.contains("Speaker A: Hello"))
        assertFalse(prompt.contains("The speech is in"))
        assertFalse(prompt.contains("spelling"))
    }
}
