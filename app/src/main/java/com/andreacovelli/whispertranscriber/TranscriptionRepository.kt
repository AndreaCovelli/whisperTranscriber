package com.andreacovelli.whispertranscriber

import androidx.room.withTransaction
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.HistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.data.TranscriptionJobDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class TranscriptionRepository @Inject constructor(
    private val db: AppDatabase,
    private val jobDao: TranscriptionJobDao,
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
        jobDao.setSummarizing(id, isSummarizing)
    }

    suspend fun updateSummaryError(id: Int, error: String?) {
        jobDao.updateSummaryError(id, error)
    }

    suspend fun succeedSummarization(id: Int, summary: String, summaryModel: String) {
        jobDao.succeedSummarization(id, summary, summaryModel)
    }

    suspend fun failSummarization(id: Int, error: String?) {
        jobDao.failSummarization(id, error)
    }

    suspend fun claimAutoSummary(id: Int): Boolean = jobDao.claimAutoSummary(id) > 0

    suspend fun claimManualSummary(id: Int): Boolean = jobDao.claimManualSummary(id) > 0

    suspend fun resetStaleSummaries(): Int = jobDao.resetStaleSummaries()

    suspend fun clearJobSummary(jobId: Int) {
        db.withTransaction {
            val historyId = jobDao.getHistoryIdByJobId(jobId)
            jobDao.updateSummary(jobId, "", "")
            jobDao.updateSummaryError(jobId, null)
            if (historyId != null) {
                historyRepository.updateSummary(historyId, "", "")
            }
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

    suspend fun updateJobById(jobId: Int, transform: (TranscriptionJob) -> TranscriptionJob) {
        val currentJob = jobDao.getJobById(jobId)
        if (currentJob != null) {
            jobDao.update(transform(currentJob))
        }
    }

    suspend fun getJob(jobId: Int): TranscriptionJob? = jobDao.getJobById(jobId)

    suspend fun retryJob(jobId: Int): Boolean = jobDao.retryById(jobId) > 0

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

    suspend fun findHistoryItem(historyId: Int): TranscriptionHistory? = historyRepository.getById(historyId)
}
