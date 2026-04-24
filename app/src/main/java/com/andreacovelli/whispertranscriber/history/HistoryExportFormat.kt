package com.andreacovelli.whispertranscriber.history

import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.google.gson.GsonBuilder
import java.io.Writer
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

private val csvHeaderRow = listOf(
    "id",
    "timestamp",
    "displayName",
    "language",
    "model",
    "summaryModel",
    "text",
    "summary",
)

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

suspend fun writeHistoryExport(
    writer: Writer,
    format: HistoryExportFormat,
    pageSize: Int = 100,
    loadPage: suspend (limit: Int, offset: Int) -> List<TranscriptionHistory>,
) {
    require(pageSize > 0) { "Page size must be positive." }

    when (format) {
        HistoryExportFormat.TXT -> writeTxtHistoryExport(writer, pageSize, loadPage)
        HistoryExportFormat.CSV -> writeCsvHistoryExport(writer, pageSize, loadPage)
        HistoryExportFormat.JSON -> writeJsonHistoryExport(writer, pageSize, loadPage)
    }
}

private fun buildTxtHistoryExport(items: List<TranscriptionHistory>): String =
    items.joinToString("\n\n---\n\n") { item ->
        buildTxtHistoryItem(item)
    }

private fun buildCsvHistoryExport(items: List<TranscriptionHistory>): String {
    val rows = buildList {
        add(csvHeaderRow)
        items.forEach { item -> add(csvRowFor(item)) }
    }
    return rows.joinToString("\r\n") { row ->
        row.joinToString(",", transform = ::escapeCsvValue)
    }
}

private fun buildJsonHistoryExport(items: List<TranscriptionHistory>): String = gson.toJson(
    items.map(::toHistoryExportRecord),
)

private suspend fun writeTxtHistoryExport(
    writer: Writer,
    pageSize: Int,
    loadPage: suspend (limit: Int, offset: Int) -> List<TranscriptionHistory>,
) {
    var offset = 0
    var wroteAny = false
    while (true) {
        val page = loadPage(pageSize, offset)
        if (page.isEmpty()) break
        page.forEach { item ->
            if (wroteAny) {
                writer.writeBlocking("\n\n---\n\n")
            }
            writer.writeBlocking(buildTxtHistoryItem(item))
            wroteAny = true
        }
        offset += page.size
    }
    require(wroteAny) { "No history to export." }
}

private suspend fun writeCsvHistoryExport(
    writer: Writer,
    pageSize: Int,
    loadPage: suspend (limit: Int, offset: Int) -> List<TranscriptionHistory>,
) {
    val firstPage = loadPage(pageSize, 0)
    require(firstPage.isNotEmpty()) { "No history to export." }

    writer.writeBlocking(csvHeaderRow.joinToString(",", transform = ::escapeCsvValue))
    var offset = 0
    while (true) {
        val page = if (offset == 0) firstPage else loadPage(pageSize, offset)
        if (page.isEmpty()) {
            break
        }
        page.forEach { item ->
            writer.writeBlocking("\r\n")
            writer.writeBlocking(csvRowFor(item).joinToString(",", transform = ::escapeCsvValue))
        }
        offset += page.size
    }
}

private suspend fun writeJsonHistoryExport(
    writer: Writer,
    pageSize: Int,
    loadPage: suspend (limit: Int, offset: Int) -> List<TranscriptionHistory>,
) {
    val firstPage = loadPage(pageSize, 0)
    require(firstPage.isNotEmpty()) { "No history to export." }

    writer.writeBlocking("[")
    var offset = 0
    var wroteAny = false
    while (true) {
        val page = if (offset == 0) firstPage else loadPage(pageSize, offset)
        if (page.isEmpty()) {
            break
        }
        page.forEach { item ->
            if (!wroteAny) {
                writer.writeBlocking("\n")
            } else {
                writer.writeBlocking(",\n")
            }
            writer.writeBlocking(gson.toJson(toHistoryExportRecord(item)))
            wroteAny = true
        }
        offset += page.size
    }
    writer.writeBlocking("\n]")
}

private fun buildTxtHistoryItem(item: TranscriptionHistory): String = buildString {
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

private fun csvRowFor(item: TranscriptionHistory): List<String> = listOf(
    item.id.toString(),
    formatHistoryTimestamp(item.timestamp),
    item.displayName,
    item.language,
    item.model,
    item.summaryModel,
    item.text,
    item.summary,
)

private fun toHistoryExportRecord(item: TranscriptionHistory): HistoryExportRecord = HistoryExportRecord(
    id = item.id,
    timestamp = formatHistoryTimestamp(item.timestamp),
    displayName = item.displayName,
    language = item.language,
    model = item.model,
    summaryModel = item.summaryModel,
    text = item.text,
    summary = item.summary,
)

private fun formatHistoryTimestamp(timestamp: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestamp))

private fun escapeCsvValue(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private fun Writer.writeBlocking(value: String) = write(value)
