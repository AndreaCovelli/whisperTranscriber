package com.andreacovelli.whispertranscriber.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED

// Lets the newest-first history pages and export walk the index instead of sorting the table.
@Entity(tableName = "transcription_history", indices = [Index(value = ["timestamp", "id"])])
data class TranscriptionHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String,
    val language: String,
    val model: String = "",
    val summary: String = "",
    val summaryProviderName: String = "",
    val summaryModel: String = "",
    val displayName: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    // Recording details captured at transcription time; empty or 0 when unknown, as for older rows.
    val providerName: String = "",
    val durationMs: Long = 0,
    val sourceSizeBytes: Long = 0,
    val speakerLabels: Boolean = false,
)

@Fts4(tokenizer = "unicode61", contentEntity = TranscriptionHistory::class)
@Entity(tableName = "transcription_history_fts")
data class TranscriptionHistoryFts(
    @PrimaryKey
    @androidx.room.ColumnInfo(name = "rowid")
    val rowId: Int,
    val text: String,
    val summary: String,
    val displayName: String,
)

@Entity(tableName = "active_jobs")
data class TranscriptionJob(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val uri: String,
    val displayName: String,
    val sourceMimeType: String? = null,
    val stagedFilePath: String? = null,
    val providerName: String = "MICROSOFT_MAI_TRANSCRIBE",
    val requestedModel: String? = null,
    val executionBackend: String = "WORK_MANAGER",
    val summarizeAfterTranscription: Boolean = false,
    val notifyResult: Boolean = false,
    val queuedSummarizerProviderName: String = "",
    val queuedSummarizerCustomModelName: String = "",
    val status: String = JOB_STATUS_QUEUED,
    // Encoded progress text while running (see StoredMessages); error and summaryError are encoded too.
    val statusMessage: String? = null,
    val progress: Float = 0f,
    val transcript: String = "",
    val language: String = "",
    val model: String = "",
    val summary: String = "",
    val summaryProviderName: String = "",
    val summaryModel: String = "",
    val summaryStatus: String = "",
    val error: String? = null,
    val summaryError: String? = null,
    val isRunning: Boolean = false,
    val isSummarizing: Boolean = false,
    val autoSummaryAttempted: Boolean = false,
    val executionOwnerId: String = "",
    val summaryAttemptId: String = "",
    val retryCount: Int = 0,
    val batchId: String? = null,
    val speakerDiarizationEnabled: Boolean = false,
    // Normalized hints captured when the job was queued or retried; see TranscriptionHints.
    val languageHint: String = "",
    val vocabulary: String = "",
    val historyId: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
)
