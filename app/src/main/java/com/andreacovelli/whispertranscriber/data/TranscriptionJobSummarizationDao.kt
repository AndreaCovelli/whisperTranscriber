package com.andreacovelli.whispertranscriber.data

import androidx.room.Dao
import androidx.room.Query
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_GENERATED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_SUMMARIZING

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
