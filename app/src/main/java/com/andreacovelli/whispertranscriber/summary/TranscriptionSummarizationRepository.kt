package com.andreacovelli.whispertranscriber.summary

import androidx.room.withTransaction
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.HistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionJobSummarizationDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptionSummarizationRepository @Inject constructor(
    private val db: AppDatabase,
    private val jobSummarizationDao: TranscriptionJobSummarizationDao,
    private val historyRepository: HistoryRepository,
) {
    suspend fun clearSummarizing(id: Int, attemptId: String): Boolean =
        jobSummarizationDao.clearSummarizing(id, attemptId) > 0

    suspend fun updateSummaryStatus(id: Int, attemptId: String, status: String): Boolean =
        jobSummarizationDao.updateSummaryStatus(id, attemptId, status) > 0

    suspend fun succeedSummarization(
        id: Int,
        attemptId: String,
        summary: String,
        summaryProviderName: String,
        summaryModel: String,
    ): Boolean = db.withTransaction {
        val updated = jobSummarizationDao.succeedSummarization(
            id,
            attemptId,
            summaryProviderName,
            summaryModel,
        ) > 0
        if (updated) {
            historyRepository.updateSummaryByJobId(id, summary, summaryProviderName, summaryModel)
        }
        updated
    }

    suspend fun failSummarization(id: Int, attemptId: String, error: String?): Boolean =
        jobSummarizationDao.failSummarization(id, attemptId, error) > 0

    suspend fun cancelSummarization(id: Int): Boolean = jobSummarizationDao.cancelSummarization(id) > 0

    /** Claims the automatic summary for the execution that owns the running job. */
    suspend fun claimAutoSummary(
        id: Int,
        executionOwnerId: String,
        attemptId: String,
        providerName: String,
        modelName: String,
    ): Boolean = jobSummarizationDao.claimBackgroundAutoSummary(
        id,
        executionOwnerId,
        attemptId,
        providerName,
        modelName,
    ) > 0

    suspend fun claimManualSummary(id: Int, attemptId: String, providerName: String, modelName: String): Boolean =
        jobSummarizationDao.claimManualSummary(id, attemptId, providerName, modelName) > 0

    suspend fun invalidateSummaryAttempt(id: Int, attemptId: String): Boolean =
        jobSummarizationDao.invalidateSummaryAttempt(id, attemptId) > 0

    suspend fun clearJobSummary(jobId: Int) {
        db.withTransaction {
            val historyId = db.transcriptionJobDao().getHistoryIdByJobId(jobId)
            if (historyId != null) {
                historyRepository.clearContent(historyId, HistoryContentField.SUMMARY)
            } else {
                db.transcriptionJobDao().clearSummaryResult(jobId, System.currentTimeMillis())
            }
        }
    }
}
