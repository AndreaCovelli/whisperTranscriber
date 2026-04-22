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

    suspend fun upsertHistoryForSummarization(
        job: TranscriptionJob,
        summary: String,
        summaryProviderName: String,
        summaryModel: String,
    ): Int = db.withTransaction {
        val existingHistoryId = jobDao.getHistoryIdByJobId(job.id)
        if (existingHistoryId != null) {
            val updatedRows = historyRepository.updateSummary(
                existingHistoryId,
                summary,
                summaryProviderName,
                summaryModel,
            )
            if (updatedRows > 0) {
                return@withTransaction existingHistoryId
            }
        }

        val newHistoryId = historyRepository.insert(
            TranscriptionHistory(
                text = job.transcript,
                language = job.language.ifBlank { "Unknown" },
                model = job.model,
                summary = summary,
                summaryProviderName = summaryProviderName,
                summaryModel = summaryModel,
                displayName = job.displayName,
            ),
        )
        jobDao.updateHistoryId(job.id, newHistoryId)
        newHistoryId
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

    suspend fun markRetryScheduled(id: Int) {
        jobDao.markRetryScheduled(id)
    }

    suspend fun updateStatus(id: Int, status: String) {
        jobDao.updateStatus(id, status)
    }

    suspend fun updateExecutionBackend(id: Int, executionBackend: String) {
        jobDao.updateExecutionBackend(id, executionBackend)
    }

    suspend fun resetRunningToQueued(id: Int): Boolean = jobDao.resetRunningToQueued(id) > 0

    suspend fun resetStaleJobs(): Int = jobDao.resetAllRunningToQueued()

    suspend fun clearJobTranscription(jobId: Int) {
        db.withTransaction {
            val historyId = jobDao.getHistoryIdByJobId(jobId)
            jobDao.clearTranscriptionResult(jobId)
            if (historyId != null) {
                historyRepository.updateText(historyId, "")
                historyRepository.updateSummary(historyId, "", "", "")
            }
        }
    }

    suspend fun getJob(jobId: Int): TranscriptionJob? = jobDao.getJobById(jobId)

    suspend fun getOldestQueuedJob(): TranscriptionJob? = jobDao.getOldestQueuedJob()

    suspend fun countBlockingJobs(): Int = jobDao.countBlockingJobs()

    suspend fun claimJobForExecution(jobId: Int, status: String): TranscriptionJob? = db.withTransaction {
        if (jobDao.claimJobForExecution(jobId, status) > 0) {
            jobDao.getJobById(jobId)
        } else {
            null
        }
    }

    suspend fun retryJob(
        jobId: Int,
        providerName: String,
        requestedModel: String?,
        executionBackend: String,
        summarizeAfterTranscription: Boolean,
        notifyResult: Boolean,
        queuedSummarizerProviderName: String,
        queuedSummarizerCustomModelName: String,
    ): Boolean = jobDao.retryById(
        jobId = jobId,
        providerName = providerName,
        requestedModel = requestedModel,
        executionBackend = executionBackend,
        summarizeAfterTranscription = summarizeAfterTranscription,
        notifyResult = notifyResult,
        queuedSummarizerProviderName = queuedSummarizerProviderName,
        queuedSummarizerCustomModelName = queuedSummarizerCustomModelName,
    ) > 0

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
