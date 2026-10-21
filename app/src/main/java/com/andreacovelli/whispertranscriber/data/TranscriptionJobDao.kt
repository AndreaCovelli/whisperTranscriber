package com.andreacovelli.whispertranscriber.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CLEARED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_COMPLETED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RETRY_SCHEDULED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RUNNING
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_SUMMARIZING
import kotlinx.coroutines.flow.Flow

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
        SET status = '$JOB_STATUS_RUNNING',
            statusMessage = :statusMessage,
            progress = :progress
        WHERE id = :id
          AND executionOwnerId = :ownerId
        """,
    )
    suspend fun updateStatusAndProgress(id: Int, ownerId: String, statusMessage: String, progress: Float): Int

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
        SET status = '$JOB_STATUS_RUNNING',
            statusMessage = :statusMessage
        WHERE id = :id
          AND executionOwnerId = :ownerId
        """,
    )
    suspend fun updateStatus(id: Int, ownerId: String, statusMessage: String): Int

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
            j.queuedSummaryStyle,
            j.queuedSummaryCustomPrompt,
            j.status,
            j.statusMessage,
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
            j.languageHint,
            j.vocabulary,
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

    @Query(
        """
        SELECT id FROM active_jobs
        WHERE historyId = :historyId
          AND summaryAttemptId != ''
          AND isRunning = 0
        """,
    )
    suspend fun getManualSummaryJobIdsByHistoryId(historyId: Int): List<Int>

    @Query(
        """
        UPDATE active_jobs
        SET isSummarizing = 0,
            summaryStatus = '$SUMMARY_STATUS_CANCELLED',
            summaryError = NULL,
            summaryAttemptId = ''
        WHERE historyId = :historyId
          AND summaryAttemptId != ''
        """,
    )
    suspend fun cancelSummarizationByHistoryId(historyId: Int): Int

    @Query("SELECT id AS jobId, stagedFilePath FROM active_jobs WHERE historyId IS NOT NULL")
    suspend fun getAllHistoryLinkedJobCleanup(): List<HistoryJobCleanup>

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
            status = '$JOB_STATUS_RUNNING',
            statusMessage = :statusMessage
        WHERE id = :jobId
          AND isRunning = 0
          AND status IN ('$JOB_STATUS_QUEUED', '$JOB_STATUS_RETRY_SCHEDULED')
          AND NOT EXISTS (SELECT 1 FROM active_jobs WHERE id != :jobId AND isRunning = 1)
        """,
    )
    suspend fun claimJobForExecution(jobId: Int, ownerId: String, statusMessage: String): Int

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
            speakerDiarizationEnabled = :speakerDiarizationEnabled,
            languageHint = :languageHint,
            vocabulary = :vocabulary
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
        languageHint: String,
        vocabulary: String,
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

    /** Rewrites the post-transcription summary choice of jobs that have not reached summarization. */
    @Query(
        """
        UPDATE active_jobs
        SET summarizeAfterTranscription = :summarizeAfterTranscription,
            queuedSummarizerProviderName = :queuedSummarizerProviderName,
            queuedSummarizerCustomModelName = :queuedSummarizerCustomModelName,
            queuedSummaryStyle = :queuedSummaryStyle,
            queuedSummaryCustomPrompt = :queuedSummaryCustomPrompt
        WHERE isSummarizing = 0
          AND (historyId IS NULL OR isRunning = 1)
        """,
    )
    suspend fun updateQueuedAutoSummary(
        summarizeAfterTranscription: Boolean,
        queuedSummarizerProviderName: String,
        queuedSummarizerCustomModelName: String,
        queuedSummaryStyle: String,
        queuedSummaryCustomPrompt: String,
    ): Int

    /** Sets the automatic-summary style of a job that [retryById] has just requeued. */
    @Query(
        """
        UPDATE active_jobs
        SET queuedSummaryStyle = :queuedSummaryStyle,
            queuedSummaryCustomPrompt = :queuedSummaryCustomPrompt
        WHERE id = :jobId
        """,
    )
    suspend fun updateQueuedSummaryStyle(jobId: Int, queuedSummaryStyle: String, queuedSummaryCustomPrompt: String)
}
