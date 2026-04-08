package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.google.gson.GsonBuilder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class HistoryExportFormat(val extension: String, val mimeType: String) {
    TXT(extension = "txt", mimeType = "text/plain"),
    CSV(extension = "csv", mimeType = "text/csv"),
    JSON(extension = "json", mimeType = "application/json"),
}

private data class HistoryExportRecord(
    val id: Int,
    val timestamp: String,
    val displayName: String,
    val language: String,
    val model: String,
    val summaryModel: String,
    val text: String,
    val summary: String,
)

private val gson = GsonBuilder()
    .disableHtmlEscaping()
    .setPrettyPrinting()
    .create()

fun buildHistoryExport(items: List<TranscriptionHistory>, format: HistoryExportFormat): String {
    require(items.isNotEmpty()) { "No history to export." }
    return when (format) {
        HistoryExportFormat.TXT -> buildTxtHistoryExport(items)
        HistoryExportFormat.CSV -> buildCsvHistoryExport(items)
        HistoryExportFormat.JSON -> buildJsonHistoryExport(items)
    }
}

fun suggestedHistoryExportFileName(format: HistoryExportFormat, clock: Clock = Clock.systemDefaultZone()): String =
    "whispertranscriber-history-${LocalDate.now(clock)}.${format.extension}"

private fun buildTxtHistoryExport(items: List<TranscriptionHistory>): String =
    items.joinToString("\n\n---\n\n") { item ->
        buildString {
            appendLine("ID: ${item.id}")
            appendLine("Timestamp: ${formatHistoryTimestamp(item.timestamp)}")
            appendLine("File name: ${item.displayName}")
            appendLine("Language: ${item.language}")
            appendLine("Model: ${item.model}")
            appendLine("Summary model: ${item.summaryModel}")
            appendLine()
            appendLine("Transcript:")
            appendLine(item.text)
            if (item.summary.isNotBlank()) {
                appendLine()
                appendLine("Summary:")
                append(item.summary)
            }
        }.trimEnd()
    }

private fun buildCsvHistoryExport(items: List<TranscriptionHistory>): String {
    val rows = buildList {
        add(
            listOf(
                "id",
                "timestamp",
                "displayName",
                "language",
                "model",
                "summaryModel",
                "text",
                "summary",
            ).joinToString(","),
        )
        items.forEach { item ->
            add(
                listOf(
                    item.id.toString(),
                    formatHistoryTimestamp(item.timestamp),
                    item.displayName,
                    item.language,
                    item.model,
                    item.summaryModel,
                    item.text,
                    item.summary,
                ).joinToString(",") { value -> escapeCsvValue(value) },
            )
        }
    }
    return rows.joinToString("\n")
}

private fun buildJsonHistoryExport(items: List<TranscriptionHistory>): String = gson.toJson(
    items.map { item ->
        HistoryExportRecord(
            id = item.id,
            timestamp = formatHistoryTimestamp(item.timestamp),
            displayName = item.displayName,
            language = item.language,
            model = item.model,
            summaryModel = item.summaryModel,
            text = item.text,
            summary = item.summary,
        )
    },
)

private fun formatHistoryTimestamp(timestamp: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestamp))

private fun escapeCsvValue(value: String): String = "\"${value.replace("\"", "\"\"")}\""
