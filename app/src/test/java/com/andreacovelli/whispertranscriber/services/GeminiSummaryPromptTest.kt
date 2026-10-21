package com.andreacovelli.whispertranscriber.services

import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSummaryPromptTest {

    @Test
    fun buildGeminiSummaryPrompt_wrapsTranscriptBeforeInstructions() {
        val prompt = buildGeminiSummaryPrompt("Hello there")

        assertTrue(prompt.startsWith("<transcript>\nHello there\n</transcript>\n\n"))
        assertTrue(prompt.contains("Summarize it for someone who has not heard the recording."))
    }

    @Test
    fun buildGeminiSummaryPrompt_preservesMultilineTranscriptVerbatim() {
        val transcript = "Speaker A: Hi\n    indented line\nSpeaker B: Bye"

        val prompt = buildGeminiSummaryPrompt(transcript)

        assertTrue(prompt.contains("<transcript>\n$transcript\n</transcript>"))
    }

    @Test
    fun buildGeminiSummaryPrompt_coversNoSpeechAndMisrecognizedWords() {
        val prompt = buildGeminiSummaryPrompt("text")

        assertTrue(prompt.contains("If the transcript contains no meaningful speech, say so in one sentence."))
        assertTrue(prompt.contains("Never guess names, numbers, or other details."))
    }
}
