package com.andreacovelli.whispertranscriber.history

import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.google.gson.stream.JsonWriter
import java.io.StringWriter
import java.io.Writer
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class HistoryExportFormat(val extension: String, val mimeType: String) {
    TXT("txt", "text/plain"),
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
}

private val csvHeaderRow = listOf(
    "id",
    "timestamp",
    "displayName",
    "language",
    "model",
    "summaryModel",
    "text",
    "summary",
    "provider",
    "durationSeconds",
    "sourceSizeBytes",
    "speakerLabels",
)

fun buildHistoryExport(items: List<TranscriptionHistory>, format: HistoryExportFormat): String =
    StringWriter().also { writeHistoryExport(it, format, items) }.toString()

fun suggestedHistoryExportFileName(format: HistoryExportFormat, clock: Clock = Clock.systemDefaultZone()): String =
    "whispertranscriber-history-${LocalDate.now(clock)}.${format.extension}"

private const val MILLISECONDS_PER_SECOND = 1_000.0

private val unsafeFileNameCharacters = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
private const val MAX_TEXT_EXPORT_BASE_NAME_LENGTH = 100

/** Derives "<audio name>-transcript.txt" or "<audio name>-summary.txt" from the audio's display name. */
fun suggestedTextExportFileName(displayName: String, field: HistoryContentField): String {
    val baseName = displayName
        .substringBeforeLast('.')
        .replace(unsafeFileNameCharacters, "_")
        .trim()
        .take(MAX_TEXT_EXPORT_BASE_NAME_LENGTH)
        .trim()
        .ifEmpty { "audio" }
    val suffix = when (field) {
        HistoryContentField.TRANSCRIPT -> "transcript"
        HistoryContentField.SUMMARY -> "summary"
    }
    return "$baseName-$suffix.${HistoryExportFormat.TXT.extension}"
}

fun writeHistoryExport(writer: Writer, format: HistoryExportFormat, items: Iterable<TranscriptionHistory>) {
    val export = HistoryExportWriter(writer, format)
    items.forEach(export::append)
    export.finish()
}

/** Explicit field names survive shrinking and are shared by both export entry points. */
internal class HistoryExportWriter(private val writer: Writer, private val format: HistoryExportFormat) {
    private var wroteAny = false
    private val json = if (format == HistoryExportFormat.JSON) JsonWriter(writer).apply { setIndent("  ") } else null

    fun append(item: TranscriptionHistory) {
        when (format) {
            HistoryExportFormat.TXT -> {
                if (wroteAny) writer.write("\n\n---\n\n")
                writer.write(buildTxtHistoryItem(item))
            }
            HistoryExportFormat.CSV -> {
                if (!wroteAny) writer.write(csvHeaderRow.joinToString(",", transform = ::escapeCsvValue))
                writer.write("\r\n")
                writer.write(csvRowFor(item).joinToString(",", transform = ::escapeCsvValue))
            }
            HistoryExportFormat.JSON -> {
                val output = requireNotNull(json)
                if (!wroteAny) output.beginArray()
                output.beginObject()
                output.name("id").value(item.id)
                output.name("timestamp").value(formatHistoryTimestamp(item.timestamp))
                output.name("displayName").value(item.displayName)
                output.name("language").value(item.language)
                output.name("model").value(item.model)
                output.name("summaryModel").value(item.summaryModel)
                output.name("text").value(item.text)
                output.name("summary").value(item.summary)
                output.name("provider").value(item.providerName)
                output.name("durationSeconds").value(item.durationMs.takeIf { it > 0 }?.let(::millisecondsToSeconds))
                output.name("sourceSizeBytes").value(item.sourceSizeBytes.takeIf { it > 0 })
                output.name("speakerLabels").value(item.speakerLabels)
                output.endObject()
            }
        }
        wroteAny = true
    }

    fun finish() {
        require(wroteAny) { "No history to export." }
        json?.endArray()
        writer.flush()
    }
}

private fun buildTxtHistoryItem(item: TranscriptionHistory): String = buildString {
    appendLine("ID: ${item.id}")
    appendLine("Timestamp: ${formatHistoryTimestamp(item.timestamp)}")
    appendLine("File name: ${item.displayName}")
    appendLine("Language: ${item.language}")
    appendLine("Model: ${item.model}")
    appendLine("Summary model: ${item.summaryModel}")
    if (item.providerName.isNotBlank()) appendLine("Provider: ${item.providerName}")
    if (item.durationMs > 0) appendLine("Duration: ${formatRecordingDuration(item.durationMs)}")
    if (item.sourceSizeBytes > 0) appendLine("Original file size: ${item.sourceSizeBytes} bytes")
    if (item.speakerLabels) appendLine("Speaker labels: yes")
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
    item.providerName,
    item.durationMs.takeIf { it > 0 }?.let(::millisecondsToSeconds)?.toString().orEmpty(),
    item.sourceSizeBytes.takeIf { it > 0 }?.toString().orEmpty(),
    item.speakerLabels.toString(),
)

private fun millisecondsToSeconds(durationMs: Long): Double = durationMs / MILLISECONDS_PER_SECOND

private fun formatHistoryTimestamp(timestamp: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestamp))

private fun escapeCsvValue(value: String): String {
    val firstMeaningfulIndex = value.indexOfFirst { character -> !character.isWhitespace() }
    val neutralized = if (
        firstMeaningfulIndex >= 0 &&
        value[firstMeaningfulIndex] in setOf('=', '+', '-', '@')
    ) {
        value.substring(0, firstMeaningfulIndex) + "'" + value.substring(firstMeaningfulIndex)
    } else {
        value
    }
    return "\"${neutralized.replace("\"", "\"\"")}\""
}
