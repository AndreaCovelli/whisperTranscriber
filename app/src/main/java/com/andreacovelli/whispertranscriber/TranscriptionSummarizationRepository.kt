package com.andreacovelli.whispertranscriber

import androidx.room.withTransaction
import com.andreacovelli.whispertranscriber.data.AppDatabase
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
    suspend fun setSummarizing(id: Int, isSummarizing: Boolean) {
        jobSummarizationDao.setSummarizing(id, isSummarizing)
    }

    suspend fun updateSummaryStatus(id: Int, status: String) {
        jobSummarizationDao.updateSummaryStatus(id, status)
    }

    suspend fun succeedSummarization(id: Int, summary: String, summaryProviderName: String, summaryModel: String) {
        jobSummarizationDao.succeedSummarization(id, summary, summaryProviderName, summaryModel)
    }

    suspend fun failSummarization(id: Int, error: String?) {
        jobSummarizationDao.failSummarization(id, error)
    }

    suspend fun cancelSummarization(id: Int): Boolean = jobSummarizationDao.cancelSummarization(id) > 0

    suspend fun claimAutoSummary(id: Int, providerName: String, modelName: String): Boolean =
        jobSummarizationDao.claimAutoSummary(id, providerName, modelName) > 0

    suspend fun claimManualSummary(id: Int, providerName: String, modelName: String): Boolean =
        jobSummarizationDao.claimManualSummary(id, providerName, modelName) > 0

    suspend fun resetStaleSummaries(): Int = jobSummarizationDao.resetStaleSummaries()

    suspend fun clearJobSummary(jobId: Int) {
        db.withTransaction {
            jobSummarizationDao.clearJobSummaryFields(jobId)
            historyRepository.updateSummaryByJobId(jobId, "", "", "")
        }
    }
}
