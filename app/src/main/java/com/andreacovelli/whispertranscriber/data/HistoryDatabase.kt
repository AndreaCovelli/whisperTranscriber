package com.andreacovelli.whispertranscriber.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CLEARED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_COMPLETED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RETRY_SCHEDULED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_GENERATED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_SUMMARIZING
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcription_history")
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
    val historyId: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
)

@Dao
interface TranscriptionHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(historyItem: TranscriptionHistory): Long

    @Query("DELETE FROM transcription_history WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM transcription_history")
    suspend fun deleteAllHistory()

    @Query("SELECT * FROM transcription_history ORDER BY timestamp DESC, id DESC")
    fun getAllHistoryPaged(): PagingSource<Int, TranscriptionHistory>

    // Blocking on purpose: export pages must run on the thread that holds the read transaction.
    @Query(
        """
        SELECT * FROM transcription_history
        WHERE :beforeTimestamp IS NULL OR timestamp < :beforeTimestamp
           OR (timestamp = :beforeTimestamp AND id < :beforeId)
        ORDER BY timestamp DESC, id DESC LIMIT :limit
        """,
    )
    fun getHistoryExportPage(limit: Int, beforeTimestamp: Long?, beforeId: Int?): List<TranscriptionHistory>

    @Query("SELECT EXISTS(SELECT 1 FROM transcription_history)")
    suspend fun hasHistory(): Boolean

    @Query(
        """
        SELECT h.*
        FROM transcription_history AS h
        INNER JOIN transcription_history_fts AS fts ON h.id = fts.rowid
        WHERE transcription_history_fts MATCH :query
        ORDER BY h.timestamp DESC, h.id DESC
        """,
    )
    fun searchHistoryPaged(query: String): PagingSource<Int, TranscriptionHistory>

    @Query(
        """
        UPDATE transcription_history
        SET summary = :summary,
            summaryProviderName = :summaryProviderName,
            summaryModel = :summaryModel
        WHERE id = :id
        """,
    )
    suspend fun updateSummary(id: Int, summary: String, summaryProviderName: String, summaryModel: String): Int

    @Query(
        """
        UPDATE transcription_history
        SET summary = :summary,
            summaryProviderName = :summaryProviderName,
            summaryModel = :summaryModel
        WHERE id = (
            SELECT historyId
            FROM active_jobs
            WHERE id = :jobId
        )
        """,
    )
    suspend fun updateSummaryByJobId(jobId: Int, summary: String, summaryProviderName: String, summaryModel: String)

    @Query("UPDATE transcription_history SET text = :text WHERE id = :id")
    suspend fun updateText(id: Int, text: String)

    @Query("SELECT * FROM transcription_history WHERE id = :id")
    suspend fun getById(id: Int): TranscriptionHistory?

    @Query(
        """
        SELECT h.*
        FROM transcription_history AS h
        INNER JOIN active_jobs AS j ON j.historyId = h.id
        WHERE j.id = :jobId
        """,
    )
    suspend fun getByJobId(jobId: Int): TranscriptionHistory?
}

@Dao
// This DAO keeps owner-checked queue state transitions together so each SQL update stays atomic.
@Suppress("TooManyFunctions")
interface TranscriptionJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: TranscriptionJob): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(jobs: List<TranscriptionJob>): List<Long>

    @Query(
        """
        UPDATE active_jobs
        SET status = :status,
            progress = :progress
        WHERE id = :id
          AND executionOwnerId = :ownerId
        """,
    )
    suspend fun updateStatusAndProgress(id: Int, ownerId: String, status: String, progress: Float): Int

    @Query(
        """
        UPDATE active_jobs
        SET transcript = '',
            language = '',
            model = '',
            historyId = :historyId,
            progress = 1,
            status = '$JOB_STATUS_COMPLETED',
            error = NULL,
            retryCount = 0
        WHERE id = :id
          AND executionOwnerId = :ownerId
          AND isRunning = 1
        """,
    )
    suspend fun updateResult(id: Int, ownerId: String, historyId: Int?): Int

    @Query(
        """
        UPDATE active_jobs
        SET error = :error,
            isRunning = 0,
            executionOwnerId = '',
            status = :status,
            retryCount = 0,
            finishedAt = :finishedAt
        WHERE id = :id
          AND executionOwnerId = :ownerId
        """,
    )
    suspend fun updateError(id: Int, ownerId: String, error: String, status: String, finishedAt: Long): Int

    @Query(
        """
        UPDATE active_jobs
        SET error = NULL,
            isRunning = 0,
            executionOwnerId = '',
            status = '$JOB_STATUS_RETRY_SCHEDULED',
            retryCount = retryCount + 1
        WHERE id = :id
          AND executionOwnerId = :ownerId
        """,
    )
    suspend fun markRetryScheduled(id: Int, ownerId: String): Int

    @Query(
        """
        UPDATE active_jobs
        SET status = :status
        WHERE id = :id
          AND executionOwnerId = :ownerId
        """,
    )
    suspend fun updateStatus(id: Int, ownerId: String, status: String): Int

    @Query("UPDATE active_jobs SET executionBackend = :executionBackend WHERE id = :id")
    suspend fun updateExecutionBackend(id: Int, executionBackend: String)

    @Query(
        """
        UPDATE active_jobs SET status = '$JOB_STATUS_ERROR', error = :message,
            finishedAt = :finishedAt, executionOwnerId = ''
        WHERE id = :id AND isRunning = 0
          AND status IN ('$JOB_STATUS_QUEUED', '$JOB_STATUS_RETRY_SCHEDULED')
        """,
    )
    suspend fun failUnstartedJob(id: Int, message: String, finishedAt: Long): Int

    @Query(
        """
        UPDATE active_jobs
        SET stagedFilePath = NULL
        WHERE id = :id
          AND executionOwnerId = :ownerId
        """,
    )
    suspend fun clearStagedFilePath(id: Int, ownerId: String): Int

    @Query(
        """
        UPDATE active_jobs
        SET isRunning = 0,
            executionOwnerId = '',
            status = '$JOB_STATUS_QUEUED',
            isSummarizing = 0,
            summaryAttemptId = '',
            summaryStatus = CASE
                WHEN isSummarizing = 1 THEN '$SUMMARY_STATUS_CANCELLED'
                ELSE summaryStatus
            END,
            autoSummaryAttempted = CASE
                WHEN isSummarizing = 1 THEN 0
                ELSE autoSummaryAttempted
            END
        WHERE id = :id
          AND isRunning = 1
          AND executionOwnerId = :ownerId
        """,
    )
    suspend fun resetRunningToQueued(id: Int, ownerId: String): Int

    @Query(
        """
        UPDATE active_jobs
        SET isRunning = 0,
            executionOwnerId = '',
            status = '$JOB_STATUS_COMPLETED',
            progress = 1,
            error = NULL,
            finishedAt = :finishedAt
        WHERE id = :id
          AND isRunning = 1
          AND executionOwnerId = :ownerId
        """,
    )
    suspend fun markRunningAsCompleted(id: Int, ownerId: String, finishedAt: Long): Int

    @Query(
        """
        UPDATE active_jobs
        SET isRunning = 0,
            executionOwnerId = '',
            status = '$JOB_STATUS_QUEUED',
            isSummarizing = 0,
            summaryAttemptId = '',
            summaryStatus = CASE
                WHEN isSummarizing = 1 THEN '$SUMMARY_STATUS_CANCELLED'
                ELSE summaryStatus
            END,
            autoSummaryAttempted = CASE
                WHEN isSummarizing = 1 THEN 0
                ELSE autoSummaryAttempted
            END
        WHERE id = :id
          AND executionOwnerId = :ownerId
          AND (isRunning = 1 OR status = '$JOB_STATUS_RETRY_SCHEDULED')
        """,
    )
    suspend fun requeueOrphanedExecution(id: Int, ownerId: String): Int

    @Query(
        """
        UPDATE active_jobs
        SET transcript = '',
            error = NULL,
            summaryError = NULL,
            progress = 0,
            status = '$JOB_STATUS_CLEARED',
            executionOwnerId = '',
            summaryAttemptId = '',
            isRunning = 0,
            isSummarizing = 0,
            autoSummaryAttempted = 1,
            notifyResult = 0,
            summaryStatus = CASE WHEN summaryStatus = '$SUMMARY_STATUS_SUMMARIZING'
                THEN '$SUMMARY_STATUS_CANCELLED' ELSE summaryStatus END,
            finishedAt = :finishedAt
        WHERE id = :id
        """,
    )
    suspend fun clearTranscriptionResult(id: Int, finishedAt: Long): Int

    @Query(
        """
        UPDATE active_jobs SET summary = '', summaryProviderName = '', summaryModel = '',
            summaryStatus = '', summaryError = NULL, summaryAttemptId = '',
            autoSummaryAttempted = 1, isSummarizing = 0, isRunning = 0, executionOwnerId = '',
            notifyResult = 0,
            status = CASE WHEN status = '$JOB_STATUS_CLEARED' THEN status ELSE '$JOB_STATUS_COMPLETED' END,
            finishedAt = :finishedAt
        WHERE id = :id
        """,
    )
    suspend fun clearSummaryResult(id: Int, finishedAt: Long): Int

    @Query(
        """
        WITH recent_terminal AS (
            SELECT id
            FROM active_jobs
            WHERE status IN (
                '$JOB_STATUS_COMPLETED',
                '$JOB_STATUS_ERROR',
                '$JOB_STATUS_CANCELLED',
                '$JOB_STATUS_CLEARED'
            )
              AND isRunning = 0
              AND isSummarizing = 0
            ORDER BY finishedAt DESC, id DESC
            LIMIT 100
        )
        SELECT
            j.id,
            j.uri,
            j.displayName,
            j.sourceMimeType,
            j.stagedFilePath,
            j.providerName,
            j.requestedModel,
            j.executionBackend,
            j.summarizeAfterTranscription,
            j.notifyResult,
            j.queuedSummarizerProviderName,
            j.queuedSummarizerCustomModelName,
            j.status,
            j.progress,
            COALESCE(h.text, j.transcript) AS transcript,
            COALESCE(h.language, j.language) AS language,
            COALESCE(h.model, j.model) AS model,
            COALESCE(h.summary, j.summary) AS summary,
            COALESCE(h.summaryProviderName, j.summaryProviderName) AS summaryProviderName,
            COALESCE(h.summaryModel, j.summaryModel) AS summaryModel,
            j.summaryStatus,
            j.error,
            j.summaryError,
            j.isRunning,
            j.isSummarizing,
            j.autoSummaryAttempted,
            j.executionOwnerId,
            j.summaryAttemptId,
            j.retryCount,
            j.batchId,
            j.speakerDiarizationEnabled,
            j.historyId,
            j.timestamp,
            j.finishedAt
        FROM active_jobs AS j
        LEFT JOIN transcription_history AS h ON h.id = j.historyId
        WHERE j.status NOT IN (
            '$JOB_STATUS_COMPLETED',
            '$JOB_STATUS_ERROR',
            '$JOB_STATUS_CANCELLED',
            '$JOB_STATUS_CLEARED'
        )
           OR j.isRunning = 1
           OR j.isSummarizing = 1
           OR j.id IN (SELECT id FROM recent_terminal)
        ORDER BY j.timestamp ASC, j.id ASC
        """,
    )
    fun getAllJobs(): Flow<List<TranscriptionJob>>

    @Query("SELECT * FROM active_jobs WHERE id = :id")
    suspend fun getJobById(id: Int): TranscriptionJob?

    @Query("SELECT historyId FROM active_jobs WHERE id = :jobId")
    suspend fun getHistoryIdByJobId(jobId: Int): Int?

    @Query("SELECT id AS jobId, stagedFilePath FROM active_jobs WHERE historyId = :historyId")
    suspend fun getJobCleanupByHistoryId(historyId: Int): List<HistoryJobCleanup>

    @Query("SELECT id AS jobId, stagedFilePath FROM active_jobs WHERE historyId IS NOT NULL")
    suspend fun getAllHistoryLinkedJobCleanup(): List<HistoryJobCleanup>

    @Query("UPDATE active_jobs SET historyId = :historyId WHERE id = :jobId")
    suspend fun updateHistoryId(jobId: Int, historyId: Int)

    @Query(
        """
        SELECT *
        FROM active_jobs
        WHERE isRunning = 1
           OR status = '$JOB_STATUS_RETRY_SCHEDULED'
        """,
    )
    suspend fun getRecoverableJobs(): List<TranscriptionJob>

    @Query(
        """
        SELECT *
        FROM active_jobs
        WHERE status = '$JOB_STATUS_QUEUED'
        ORDER BY timestamp ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun getOldestQueuedJob(): TranscriptionJob?

    @Query(
        """
        SELECT COUNT(*)
        FROM active_jobs
        WHERE isRunning = 1
           OR status = '$JOB_STATUS_RETRY_SCHEDULED'
        """,
    )
    suspend fun countBlockingJobs(): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM active_jobs
        WHERE status NOT IN (
            '$JOB_STATUS_COMPLETED',
            '$JOB_STATUS_ERROR',
            '$JOB_STATUS_CANCELLED',
            '$JOB_STATUS_CLEARED'
        )
           OR isRunning = 1
           OR isSummarizing = 1
        """,
    )
    suspend fun countNonterminalJobs(): Int

    @Query(
        """
        UPDATE active_jobs
        SET isRunning = 1,
            executionOwnerId = :ownerId,
            status = :status
        WHERE id = :jobId
          AND isRunning = 0
          AND status IN ('$JOB_STATUS_QUEUED', '$JOB_STATUS_RETRY_SCHEDULED')
          AND NOT EXISTS (SELECT 1 FROM active_jobs WHERE id != :jobId AND isRunning = 1)
        """,
    )
    suspend fun claimJobForExecution(jobId: Int, ownerId: String, status: String): Int

    @Query(
        """
        UPDATE active_jobs
        SET status = '$JOB_STATUS_QUEUED',
            progress = 0,
            transcript = '',
            language = '',
            model = '',
            summary = '',
            summaryProviderName = '',
            summaryModel = '',
            summaryStatus = '',
            error = NULL,
            summaryError = NULL,
            isRunning = 0,
            isSummarizing = 0,
            autoSummaryAttempted = 0,
            executionOwnerId = '',
            summaryAttemptId = '',
            retryCount = 0,
            finishedAt = 0,
            historyId = NULL,
            providerName = :providerName,
            requestedModel = :requestedModel,
            executionBackend = :executionBackend,
            summarizeAfterTranscription = :summarizeAfterTranscription,
            notifyResult = :notifyResult,
            queuedSummarizerProviderName = :queuedSummarizerProviderName,
            queuedSummarizerCustomModelName = :queuedSummarizerCustomModelName,
            speakerDiarizationEnabled = :speakerDiarizationEnabled
        WHERE id = :jobId
          AND status IN ('$JOB_STATUS_ERROR', '$JOB_STATUS_CANCELLED')
          AND isRunning = 0 AND isSummarizing = 0
        """,
    )
    suspend fun retryById(
        jobId: Int,
        providerName: String,
        requestedModel: String?,
        executionBackend: String,
        summarizeAfterTranscription: Boolean,
        notifyResult: Boolean,
        queuedSummarizerProviderName: String,
        queuedSummarizerCustomModelName: String,
        speakerDiarizationEnabled: Boolean,
    ): Int

    @Query(
        """
        SELECT *
        FROM active_jobs
        WHERE status IN (
            '$JOB_STATUS_COMPLETED',
            '$JOB_STATUS_ERROR',
            '$JOB_STATUS_CANCELLED',
            '$JOB_STATUS_CLEARED'
        )
          AND isRunning = 0
          AND isSummarizing = 0
        """,
    )
    suspend fun getCompletedJobs(): List<TranscriptionJob>

    @Query(
        """
        SELECT *
        FROM active_jobs
        WHERE finishedAt > 0
          AND finishedAt < :cutoff
          AND status IN (
              '$JOB_STATUS_COMPLETED',
              '$JOB_STATUS_ERROR',
              '$JOB_STATUS_CANCELLED',
              '$JOB_STATUS_CLEARED'
          )
          AND isRunning = 0
          AND isSummarizing = 0
        """,
    )
    suspend fun getExpiredTerminalJobs(cutoff: Long): List<TranscriptionJob>

    @Query(
        """
        DELETE FROM active_jobs
        WHERE id = :jobId
          AND executionOwnerId = ''
          AND summaryAttemptId = ''
        """,
    )
    suspend fun deleteById(jobId: Int): Int

    @Query(
        """
        UPDATE active_jobs
        SET executionOwnerId = '',
            summaryAttemptId = '',
            isRunning = 0,
            isSummarizing = 0,
            summaryStatus = CASE
                WHEN summaryStatus = '$SUMMARY_STATUS_SUMMARIZING' THEN '$SUMMARY_STATUS_CANCELLED'
                ELSE summaryStatus
            END
        WHERE historyId = :historyId
        """,
    )
    suspend fun invalidateOwnersByHistoryId(historyId: Int): Int

    @Query(
        """
        UPDATE active_jobs
        SET executionOwnerId = '',
            summaryAttemptId = '',
            isRunning = 0,
            isSummarizing = 0,
            summaryStatus = CASE
                WHEN summaryStatus = '$SUMMARY_STATUS_SUMMARIZING' THEN '$SUMMARY_STATUS_CANCELLED'
                ELSE summaryStatus
            END
        WHERE historyId IS NOT NULL
        """,
    )
    suspend fun invalidateAllHistoryLinkedOwners(): Int

    @Query(
        """
        DELETE FROM active_jobs
        WHERE historyId = :historyId
        """,
    )
    suspend fun deleteByHistoryId(historyId: Int): Int

    @Query(
        """
        DELETE FROM active_jobs
        WHERE historyId IS NOT NULL
        """,
    )
    suspend fun deleteAllWithHistory(): Int

    @Query(
        """
        DELETE FROM active_jobs
        WHERE id IN (:jobIds)
          AND status IN (
              '$JOB_STATUS_COMPLETED',
              '$JOB_STATUS_ERROR',
              '$JOB_STATUS_CANCELLED',
              '$JOB_STATUS_CLEARED'
          )
          AND isRunning = 0
          AND isSummarizing = 0
          AND executionOwnerId = ''
          AND summaryAttemptId = ''
        """,
    )
    suspend fun deleteCompletedByIds(jobIds: List<Int>): Int

    @Query(
        """
        UPDATE active_jobs
        SET executionOwnerId = '',
            summaryAttemptId = '',
            isRunning = 0,
            isSummarizing = 0
        WHERE id = :jobId
        """,
    )
    suspend fun invalidateOwners(jobId: Int): Int

    @Query(
        """
        UPDATE active_jobs
        SET isSummarizing = 0,
            summaryAttemptId = '',
            summaryStatus = '$SUMMARY_STATUS_CANCELLED',
            autoSummaryAttempted = 0
        WHERE isSummarizing = 1
          AND isRunning = 0
          AND summaryAttemptId != ''
          AND summaryAttemptId NOT LIKE :sessionPrefix || '%'
        """,
    )
    suspend fun cancelOrphanedUiSummaries(sessionPrefix: String): Int

    @Query(
        """
        UPDATE active_jobs
        SET executionOwnerId = '',
            summaryAttemptId = '',
            isRunning = 0,
            isSummarizing = 0,
            status = CASE WHEN historyId IS NULL THEN '$JOB_STATUS_CANCELLED' ELSE '$JOB_STATUS_COMPLETED' END,
            summaryStatus = CASE
                WHEN summaryStatus = '$SUMMARY_STATUS_SUMMARIZING' THEN '$SUMMARY_STATUS_CANCELLED'
                ELSE summaryStatus
            END,
            error = CASE WHEN historyId IS NULL THEN :cancelledMessage ELSE NULL END,
            finishedAt = :finishedAt
        WHERE id = :jobId
        """,
    )
    suspend fun cancelJob(jobId: Int, cancelledMessage: String, finishedAt: Long): Int

    @Query(
        """
        DELETE FROM active_jobs
        WHERE finishedAt > 0
          AND finishedAt < :cutoff
          AND status IN (
              '$JOB_STATUS_COMPLETED',
              '$JOB_STATUS_ERROR',
              '$JOB_STATUS_CANCELLED',
              '$JOB_STATUS_CLEARED'
          )
          AND isRunning = 0
          AND isSummarizing = 0
        """,
    )
    suspend fun deleteExpiredTerminalJobs(cutoff: Long): Int

    @Query(
        """
        SELECT stagedFilePath
        FROM active_jobs
        WHERE stagedFilePath IS NOT NULL
          AND (
              status IN (
                  '$JOB_STATUS_QUEUED',
                  '$JOB_STATUS_RETRY_SCHEDULED',
                  '$JOB_STATUS_ERROR',
                  '$JOB_STATUS_CANCELLED'
              )
              OR isRunning = 1
              OR isSummarizing = 1
          )
        """,
    )
    suspend fun getReferencedStagedFilePaths(): List<String>

    /** Excludes already-finished jobs from automatic summarization; only later completions qualify. */
    @Query(
        """
        UPDATE active_jobs
        SET autoSummaryAttempted = 1
        WHERE isRunning = 0
          AND isSummarizing = 0
          AND autoSummaryAttempted = 0
          AND historyId IS NOT NULL
        """,
    )
    suspend fun markFinishedJobsAutoSummaryAttempted(): Int
}

@Dao
interface TranscriptionJobSummarizationDao {
    @Query(
        """
        UPDATE active_jobs
        SET summaryStatus = :status
        WHERE id = :id
          AND summaryAttemptId = :attemptId
        """,
    )
    suspend fun updateSummaryStatus(id: Int, attemptId: String, status: String): Int

    @Query(
        """
        UPDATE active_jobs
        SET isSummarizing = 0,
            summaryAttemptId = ''
        WHERE id = :id
          AND summaryAttemptId = :attemptId
        """,
    )
    suspend fun clearSummarizing(id: Int, attemptId: String): Int

    @Query(
        """
        UPDATE active_jobs
        SET summary = '',
            summaryProviderName = '',
            summaryModel = '',
            summaryStatus = '',
            summaryError = NULL,
            isSummarizing = 0,
            summaryAttemptId = ''
        WHERE id = :id
          AND isRunning = 0
        """,
    )
    suspend fun clearJobSummaryFields(id: Int): Int

    @Query(
        """
        UPDATE active_jobs
        SET isSummarizing = 0,
            summary = '',
            summaryProviderName = :summaryProviderName,
            summaryModel = :summaryModel,
            summaryStatus = '$SUMMARY_STATUS_GENERATED',
            summaryError = NULL,
            summaryAttemptId = ''
        WHERE id = :id
          AND summaryAttemptId = :attemptId
        """,
    )
    suspend fun succeedSummarization(
        id: Int,
        attemptId: String,
        summaryProviderName: String,
        summaryModel: String,
    ): Int

    @Query(
        """
        UPDATE active_jobs
        SET isSummarizing = 0,
            summaryStatus = '$SUMMARY_STATUS_ERROR',
            summaryError = :error,
            summaryAttemptId = ''
        WHERE id = :id
          AND summaryAttemptId = :attemptId
        """,
    )
    suspend fun failSummarization(id: Int, attemptId: String, error: String?): Int

    @Query(
        """
        UPDATE active_jobs
        SET isSummarizing = 0,
            summaryStatus = '$SUMMARY_STATUS_CANCELLED',
            summaryError = NULL,
            summaryAttemptId = ''
        WHERE id = :id
          AND summaryAttemptId != ''
        """,
    )
    suspend fun cancelSummarization(id: Int): Int

    @Query(
        """
        UPDATE active_jobs
        SET isSummarizing = 1,
            summary = '',
            summaryProviderName = :providerName,
            summaryModel = :modelName,
            summaryStatus = '$SUMMARY_STATUS_SUMMARIZING',
            summaryError = NULL,
            autoSummaryAttempted = 1,
            summaryAttemptId = :attemptId
        WHERE id = :id
          AND isRunning = 0
          AND isSummarizing = 0
          AND autoSummaryAttempted = 0
          AND historyId IS NOT NULL
          AND summary = ''
          AND error IS NULL
          AND summaryError IS NULL
        """,
    )
    suspend fun claimAutoSummary(id: Int, attemptId: String, providerName: String, modelName: String): Int

    @Query(
        """
        UPDATE active_jobs
        SET isSummarizing = 1,
            summary = '',
            summaryProviderName = :providerName,
            summaryModel = :modelName,
            summaryStatus = '$SUMMARY_STATUS_SUMMARIZING',
            summaryError = NULL,
            autoSummaryAttempted = 1,
            summaryAttemptId = :attemptId
        WHERE id = :id
          AND isRunning = 1
          AND executionOwnerId = :executionOwnerId
          AND isSummarizing = 0
          AND autoSummaryAttempted = 0
          AND historyId IS NOT NULL
          AND summary = ''
          AND error IS NULL
          AND summaryError IS NULL
        """,
    )
    suspend fun claimBackgroundAutoSummary(
        id: Int,
        executionOwnerId: String,
        attemptId: String,
        providerName: String,
        modelName: String,
    ): Int

    @Query(
        """
        UPDATE active_jobs
        SET isSummarizing = 1,
            summary = '',
            summaryProviderName = :providerName,
            summaryModel = :modelName,
            summaryStatus = '$SUMMARY_STATUS_SUMMARIZING',
            summaryError = NULL,
            autoSummaryAttempted = 1,
            summaryAttemptId = :attemptId
        WHERE id = :id
          AND isRunning = 0
          AND isSummarizing = 0
          AND historyId IS NOT NULL
          AND error IS NULL
        """,
    )
    suspend fun claimManualSummary(id: Int, attemptId: String, providerName: String, modelName: String): Int

    @Query(
        """
        UPDATE active_jobs
        SET isSummarizing = 0,
            summaryAttemptId = '',
            summaryStatus = '$SUMMARY_STATUS_CANCELLED',
            autoSummaryAttempted = 0
        WHERE id = :id
          AND summaryAttemptId = :attemptId
        """,
    )
    suspend fun invalidateSummaryAttempt(id: Int, attemptId: String): Int
}

@Database(
    entities = [TranscriptionHistory::class, TranscriptionHistoryFts::class, TranscriptionJob::class],
    version = 15,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptionHistoryDao(): TranscriptionHistoryDao
    abstract fun transcriptionJobDao(): TranscriptionJobDao
    abstract fun transcriptionJobSummarizationDao(): TranscriptionJobSummarizationDao

    companion object {
        private const val DATABASE_NAME = "transcription_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcription_history ADD COLUMN model TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcription_history ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcription_history ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `active_jobs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `uri` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `progress` REAL NOT NULL,
                        `transcript` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `model` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `error` TEXT,
                        `isRunning` INTEGER NOT NULL,
                        `isSummarizing` INTEGER NOT NULL,
                        `historyId` INTEGER,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN summaryError TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN sourceMimeType TEXT")
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN stagedFilePath TEXT")
                db.execSQL(
                    "ALTER TABLE active_jobs ADD COLUMN providerName TEXT NOT NULL DEFAULT 'MICROSOFT_MAI_TRANSCRIBE'",
                )
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN requestedModel TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS `transcription_history_fts`
                    USING fts4(
                        `text`,
                        `summary`,
                        `displayName`,
                        content=`transcription_history`,
                        tokenize=unicode61
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `transcription_history_fts`(`transcription_history_fts`)
                    VALUES('rebuild')
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcription_history ADD COLUMN summaryModel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN summaryModel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN batchId TEXT")
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN speakerDiarizationEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN summaryStatus TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN autoSummaryAttempted INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcription_history ADD COLUMN summaryProviderName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN summaryProviderName TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN executionBackend TEXT NOT NULL DEFAULT 'WORK_MANAGER'")
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN summarizeAfterTranscription INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN notifyResult INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN queuedSummarizerProviderName TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "ALTER TABLE active_jobs ADD COLUMN queuedSummarizerCustomModelName TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE active_jobs
                    SET status = CASE status
                        WHEN 'Queued' THEN '$JOB_STATUS_QUEUED'
                        WHEN 'Retry scheduled' THEN '$JOB_STATUS_RETRY_SCHEDULED'
                        WHEN 'Completed' THEN '$JOB_STATUS_COMPLETED'
                        WHEN 'Cleared' THEN '$JOB_STATUS_CLEARED'
                        WHEN 'Error' THEN '$JOB_STATUS_ERROR'
                        WHEN 'Cancelled' THEN '$JOB_STATUS_CANCELLED'
                        ELSE status
                    END
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE active_jobs
                    SET summaryStatus = CASE summaryStatus
                        WHEN 'Summarizing...' THEN '$SUMMARY_STATUS_SUMMARIZING'
                        WHEN 'Summary generated' THEN '$SUMMARY_STATUS_GENERATED'
                        WHEN 'Error' THEN '$SUMMARY_STATUS_ERROR'
                        WHEN 'Cancelled' THEN '$SUMMARY_STATUS_CANCELLED'
                        ELSE summaryStatus
                    END
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN executionOwnerId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN summaryAttemptId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN finishedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE active_jobs
                    SET status = CASE
                            WHEN isRunning = 1 AND historyId IS NULL THEN '$JOB_STATUS_QUEUED'
                            WHEN status = '$JOB_STATUS_RETRY_SCHEDULED' THEN '$JOB_STATUS_QUEUED'
                            WHEN isRunning = 1 AND historyId IS NOT NULL THEN '$JOB_STATUS_COMPLETED'
                            ELSE status
                        END,
                        summaryStatus = CASE
                            WHEN isSummarizing = 1 THEN '$SUMMARY_STATUS_CANCELLED'
                            ELSE summaryStatus
                        END,
                        autoSummaryAttempted = CASE
                            WHEN isSummarizing = 1 THEN 0
                            ELSE autoSummaryAttempted
                        END,
                        isRunning = 0,
                        isSummarizing = 0,
                        executionOwnerId = '',
                        summaryAttemptId = '',
                        finishedAt = CASE
                            WHEN status IN (
                                '$JOB_STATUS_COMPLETED',
                                '$JOB_STATUS_ERROR',
                                '$JOB_STATUS_CANCELLED',
                                '$JOB_STATUS_CLEARED'
                            ) OR (isRunning = 1 AND historyId IS NOT NULL)
                            THEN timestamp
                            ELSE 0
                        END
                    """.trimIndent(),
                )
            }
        }

        val MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
        )

        private fun createBuilder(context: Context) = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            DATABASE_NAME,
        ).apply {
            MIGRATIONS.forEach { migration ->
                addMigrations(migration)
            }
        }

        fun getDatabase(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: createBuilder(context).build().also { instance ->
                INSTANCE = instance
            }
        }
    }
}
