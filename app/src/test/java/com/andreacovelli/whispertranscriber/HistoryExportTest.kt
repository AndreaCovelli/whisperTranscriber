package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.history.HistoryExportFormat
import com.andreacovelli.whispertranscriber.history.buildHistoryExport
import com.andreacovelli.whispertranscriber.history.formatRecordingDuration
import com.andreacovelli.whispertranscriber.history.suggestedTextExportFileName
import com.andreacovelli.whispertranscriber.history.writeHistoryExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HistoryExportTest {
    @Test
    fun suggestedTextExportFileName_replacesTheAudioExtensionWithTheContentKind() {
        assertEquals(
            "meeting.notes-transcript.txt",
            suggestedTextExportFileName("meeting.notes.m4a", HistoryContentField.TRANSCRIPT),
        )
        assertEquals("voice memo-summary.txt", suggestedTextExportFileName("voice memo", HistoryContentField.SUMMARY))
    }

    @Test
    fun suggestedTextExportFileName_sanitizesUnsafeAndBlankNames() {
        assertEquals(
            "a_b_c__ d-transcript.txt",
            suggestedTextExportFileName("a/b:c?\n d.wav", HistoryContentField.TRANSCRIPT),
        )
        assertEquals("audio-summary.txt", suggestedTextExportFileName(" .wav", HistoryContentField.SUMMARY))
        assertEquals("audio-transcript.txt", suggestedTextExportFileName("", HistoryContentField.TRANSCRIPT))
        assertEquals(
            "x".repeat(100) + "-summary.txt",
            suggestedTextExportFileName("x".repeat(300) + ".mp3", HistoryContentField.SUMMARY),
        )
    }

    @Test
    fun buildHistoryExport_formatsTxtWithSummaryAndMetadata() {
        val export = buildHistoryExport(listOf(historyItem()), HistoryExportFormat.TXT)

        assertTrue(export.contains("ID: 7"))
        assertTrue(export.contains("Timestamp: 2026-03-07T23:04:16Z"))
        assertTrue(export.contains("File name: meeting.wav"))
        assertTrue(export.contains("Language: en"))
        assertTrue(export.contains("Model: whisper-large-v3"))
        assertTrue(export.contains("Summary model: gemini-2.5-flash"))
        assertTrue(export.contains("Transcript:\nTranscript body"))
        assertTrue(export.contains("Summary:\nSummary body"))
    }

    @Test
    fun buildHistoryExport_includesRecordingDetailsWhenKnown() {
        val item = historyItem().copy(
            providerName = "MICROSOFT_MAI_TRANSCRIBE",
            durationMs = 3_723_400L,
            sourceSizeBytes = 52_428_800L,
            speakerLabels = true,
        )

        val txt = buildHistoryExport(listOf(item), HistoryExportFormat.TXT)
        assertTrue(txt.contains("Provider: MICROSOFT_MAI_TRANSCRIBE"))
        assertTrue(txt.contains("Duration: 1:02:03"))
        assertTrue(txt.contains("Original file size: 52428800 bytes"))
        assertTrue(txt.contains("Speaker labels: yes"))

        val csv = buildHistoryExport(listOf(item), HistoryExportFormat.CSV)
        assertTrue(csv.endsWith("\"MICROSOFT_MAI_TRANSCRIBE\",\"3723.4\",\"52428800\",\"true\""))

        val json = buildHistoryExport(listOf(item), HistoryExportFormat.JSON)
        assertTrue(json.contains("\"provider\": \"MICROSOFT_MAI_TRANSCRIBE\""))
        assertTrue(json.contains("\"durationSeconds\": 3723.4"))
        assertTrue(json.contains("\"sourceSizeBytes\": 52428800"))
        assertTrue(json.contains("\"speakerLabels\": true"))
    }

    @Test
    fun buildHistoryExport_omitsUnknownRecordingDetails() {
        val txt = buildHistoryExport(listOf(historyItem()), HistoryExportFormat.TXT)
        assertFalse(txt.contains("Provider:"))
        assertFalse(txt.contains("Duration:"))
        assertFalse(txt.contains("Original file size:"))
        assertFalse(txt.contains("Speaker labels:"))

        val json = buildHistoryExport(listOf(historyItem()), HistoryExportFormat.JSON)
        assertTrue(json.contains("\"durationSeconds\": null"))
        assertTrue(json.contains("\"sourceSizeBytes\": null"))
        assertTrue(json.contains("\"speakerLabels\": false"))
    }

    @Test
    fun formatRecordingDuration_usesMinutesOrHours() {
        assertEquals("0:00", formatRecordingDuration(0))
        assertEquals("0:01", formatRecordingDuration(500))
        assertEquals("4:05", formatRecordingDuration(245_000))
        assertEquals("1:00:00", formatRecordingDuration(3_599_600))
        assertEquals("12:03:09", formatRecordingDuration(43_389_000))
    }

    @Test
    fun buildHistoryExport_escapesCsvFields() {
        val export = buildHistoryExport(
            listOf(
                historyItem(
                    displayName = "quarterly,\"review\".wav",
                    text = "Line 1,\nLine 2",
                    summary = "Needs \"quotes\"",
                ),
            ),
            HistoryExportFormat.CSV,
        )

        assertEquals(
            buildString {
                append(CSV_HEADER)
                append("\"7\",\"2026-03-07T23:04:16Z\",\"quarterly,\"\"review\"\".wav\",\"en\",")
                append("\"whisper-large-v3\",\"gemini-2.5-flash\",\"Line 1,\nLine 2\",\"Needs \"\"quotes\"\"\",")
                append("\"\",\"\",\"\",\"false\"")
            },
            export,
        )
    }

    @Test
    fun buildHistoryExport_neutralizesSpreadsheetFormulaCells() {
        val export = buildHistoryExport(
            listOf(
                historyItem(
                    displayName = "=HYPERLINK(\"https://example.invalid\")",
                    text = " \t+SUM(1,2)",
                    summary = "@malicious",
                ),
            ),
            HistoryExportFormat.CSV,
        )

        assertTrue(export.contains("\"'=HYPERLINK(\"\"https://example.invalid\"\")\""))
        assertTrue(export.contains("\" \t'+SUM(1,2)\""))
        assertTrue(export.contains("\"'@malicious\""))
    }

    @Test
    fun writeHistoryExport_streamsCsvWithEscapedHeaderAndCrLfRows() {
        val writer = java.io.StringWriter()

        kotlinx.coroutines.runBlocking {
            writeHistoryExport(
                writer = writer,
                format = HistoryExportFormat.CSV,
                items = listOf(
                    historyItem(id = 2, timestamp = 2_000L, text = "Newest"),
                    historyItem(id = 1, timestamp = 1_000L, text = "Older"),
                ),
            )
        }

        assertEquals(
            CSV_HEADER +
                "\"2\",\"1970-01-01T00:00:02Z\",\"meeting.wav\",\"en\",\"whisper-large-v3\"," +
                "\"gemini-2.5-flash\",\"Newest\",\"Summary body\",\"\",\"\",\"\",\"false\"\r\n" +
                "\"1\",\"1970-01-01T00:00:01Z\",\"meeting.wav\",\"en\",\"whisper-large-v3\"," +
                "\"gemini-2.5-flash\",\"Older\",\"Summary body\",\"\",\"\",\"\",\"false\"",
            writer.toString(),
        )
    }

    @Test
    fun writeHistoryExport_rejectsEmptyCsvWithoutWritingPartialContent() {
        assertExportFailsWithNoOutput(HistoryExportFormat.CSV)
    }

    @Test
    fun writeHistoryExport_rejectsEmptyJsonWithoutWritingPartialContent() {
        assertExportFailsWithNoOutput(HistoryExportFormat.JSON)
    }

    @Test
    fun buildHistoryExport_formatsJsonInDescendingOrder() {
        val export = buildHistoryExport(
            listOf(
                historyItem(id = 2, timestamp = 2_000L, text = "Newest"),
                historyItem(id = 1, timestamp = 1_000L, text = "Older"),
            ),
            HistoryExportFormat.JSON,
        )

        assertTrue(export.contains("\"id\": 2"))
        assertTrue(export.contains("\"timestamp\": \"1970-01-01T00:00:02Z\""))
        assertTrue(export.indexOf("Newest") < export.indexOf("Older"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildHistoryExport_rejectsEmptyHistory() {
        buildHistoryExport(emptyList(), HistoryExportFormat.JSON)
    }

    private companion object {
        const val CSV_HEADER = "\"id\",\"timestamp\",\"displayName\",\"language\",\"model\",\"summaryModel\"," +
            "\"text\",\"summary\",\"provider\",\"durationSeconds\",\"sourceSizeBytes\",\"speakerLabels\"\r\n"
    }

    private fun historyItem(
        id: Int = 7,
        timestamp: Long = 1_772_924_656_000L,
        displayName: String = "meeting.wav",
        language: String = "en",
        model: String = "whisper-large-v3",
        summaryModel: String = "gemini-2.5-flash",
        text: String = "Transcript body",
        summary: String = "Summary body",
    ) = TranscriptionHistory(
        id = id,
        text = text,
        language = language,
        model = model,
        summary = summary,
        summaryModel = summaryModel,
        displayName = displayName,
        timestamp = timestamp,
    )

    private fun assertExportFailsWithNoOutput(format: HistoryExportFormat) {
        val writer = java.io.StringWriter()

        try {
            kotlinx.coroutines.runBlocking {
                writeHistoryExport(
                    writer = writer,
                    format = format,
                    items = emptyList(),
                )
            }
            fail("Expected export to fail for empty history.")
        } catch (_: IllegalArgumentException) {
            assertEquals("", writer.toString())
        }
    }
}
