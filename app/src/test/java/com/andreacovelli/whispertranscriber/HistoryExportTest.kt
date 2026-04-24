package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.history.HistoryExportFormat
import com.andreacovelli.whispertranscriber.history.buildHistoryExport
import com.andreacovelli.whispertranscriber.history.writeHistoryExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HistoryExportTest {
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
                append(
                    "\"id\",\"timestamp\",\"displayName\",\"language\",\"model\",\"summaryModel\",\"text\",\"summary\"\r\n",
                )
                append("\"7\",\"2026-03-07T23:04:16Z\",\"quarterly,\"\"review\"\".wav\",\"en\",")
                append("\"whisper-large-v3\",\"gemini-2.5-flash\",\"Line 1,\nLine 2\",\"Needs \"\"quotes\"\"\"")
            },
            export,
        )
    }

    @Test
    fun writeHistoryExport_streamsCsvWithEscapedHeaderAndCrLfRows() {
        val writer = java.io.StringWriter()

        kotlinx.coroutines.runBlocking {
            writeHistoryExport(
                writer = writer,
                format = HistoryExportFormat.CSV,
                pageSize = 1,
            ) { limit, offset ->
                listOf(
                    historyItem(id = 2, timestamp = 2_000L, text = "Newest"),
                    historyItem(id = 1, timestamp = 1_000L, text = "Older"),
                ).drop(offset).take(limit)
            }
        }

        assertEquals(
            "\"id\",\"timestamp\",\"displayName\",\"language\",\"model\",\"summaryModel\",\"text\",\"summary\"\r\n" +
                "\"2\",\"1970-01-01T00:00:02Z\",\"meeting.wav\",\"en\",\"whisper-large-v3\"," +
                "\"gemini-2.5-flash\",\"Newest\",\"Summary body\"\r\n" +
                "\"1\",\"1970-01-01T00:00:01Z\",\"meeting.wav\",\"en\",\"whisper-large-v3\"," +
                "\"gemini-2.5-flash\",\"Older\",\"Summary body\"",
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
                    pageSize = 1,
                ) { _, _ -> emptyList() }
            }
            fail("Expected export to fail for empty history.")
        } catch (_: IllegalArgumentException) {
            assertEquals("", writer.toString())
        }
    }
}
