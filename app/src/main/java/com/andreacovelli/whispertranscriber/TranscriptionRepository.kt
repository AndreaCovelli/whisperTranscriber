package com.andreacovelli.whispertranscriber

import androidx.room.withTransaction
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.HistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.data.TranscriptionJobDao
import com.andreacovelli.whispertranscriber.data.TranscriptionJobSummarizationDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class TranscriptionRepository @Inject constructor(
    private val db: AppDatabase,
    private val jobDao: TranscriptionJobDao,
    private val jobSummarizationDao: TranscriptionJobSummarizationDao,
    private val historyRepository: HistoryRepository,
) {

    val jobsState: Flow<List<TranscriptionJob>> = jobDao.getAllJobs()

    suspend fun addJob(job: TranscriptionJob): Long = jobDao.insert(job)

    suspend fun insertHistory(historyItem: TranscriptionHistory): Int = historyRepository.insert(historyItem)

    suspend fun updateHistorySummary(historyId: Int, summary: String, summaryModel: String) {
        historyRepository.updateSummary(historyId, summary, summaryModel)
    }

    suspend fun updateRunning(id: Int, isRunning: Boolean, status: String) {
        jobDao.updateRunning(id, isRunning, status)
    }

    suspend fun updateStatusAndProgress(id: Int, status: String, progress: Float) {
        jobDao.updateStatusAndProgress(id, status, progress)
    }

    suspend fun updateResult(
        id: Int,
        transcript: String,
        language: String,
        model: String,
        historyId: Int?,
        progress: Float,
        isRunning: Boolean,
        status: String,
        error: String?,
    ) {
        jobDao.updateResult(id, transcript, language, model, historyId, progress, isRunning, status, error)
    }

    suspend fun updateError(id: Int, error: String, isRunning: Boolean, status: String) {
        jobDao.updateError(id, error, isRunning, status)
    }

    suspend fun updateStatus(id: Int, status: String) {
        jobDao.updateStatus(id, status)
    }

    suspend fun setSummarizing(id: Int, isSummarizing: Boolean) {
        jobSummarizationDao.setSummarizing(id, isSummarizing)
    }

    suspend fun updateSummaryStatus(id: Int, status: String) {
        jobSummarizationDao.updateSummaryStatus(id, status)
    }

    suspend fun succeedSummarization(id: Int, summary: String, summaryModel: String) {
        jobSummarizationDao.succeedSummarization(id, summary, summaryModel)
    }

    suspend fun failSummarization(id: Int, error: String?) {
        jobSummarizationDao.failSummarization(id, error)
    }

    suspend fun cancelSummarization(id: Int): Boolean = jobSummarizationDao.cancelSummarization(id) > 0

    suspend fun claimAutoSummary(id: Int, modelName: String): Boolean =
        jobSummarizationDao.claimAutoSummary(id, modelName) > 0

    suspend fun claimManualSummary(id: Int, modelName: String): Boolean =
        jobSummarizationDao.claimManualSummary(id, modelName) > 0

    suspend fun resetStaleSummaries(): Int = jobSummarizationDao.resetStaleSummaries()

    suspend fun clearJobSummary(jobId: Int) {
        db.withTransaction {
            jobSummarizationDao.clearJobSummaryFields(jobId)
            historyRepository.updateSummaryByJobId(jobId, "", "")
        }
    }

    suspend fun clearJobTranscription(jobId: Int) {
        db.withTransaction {
            val historyId = jobDao.getHistoryIdByJobId(jobId)
            jobDao.clearTranscriptionResult(jobId)
            if (historyId != null) {
                historyRepository.updateText(historyId, "")
            }
        }
    }

    suspend fun getJob(jobId: Int): TranscriptionJob? = jobDao.getJobById(jobId)

    suspend fun getQueuedJobs(): List<TranscriptionJob> = jobDao.getQueuedJobs()

    suspend fun retryJob(jobId: Int, providerName: String, requestedModel: String?): Boolean =
        jobDao.retryById(jobId, providerName, requestedModel) > 0

    suspend fun clearCompletedJobs(): List<String> {
        val completedJobs = jobDao.getCompletedJobs()
        jobDao.clearCompleted()
        return completedJobs.mapNotNull { it.stagedFilePath }
    }

    suspend fun deleteJob(jobId: Int): String? {
        val job = jobDao.getJobById(jobId)
        jobDao.deleteById(jobId)
        return job?.stagedFilePath
    }
}
