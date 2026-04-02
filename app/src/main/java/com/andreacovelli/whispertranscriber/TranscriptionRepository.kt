package com.andreacovelli.whispertranscriber

import android.content.Context
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import androidx.room.withTransaction
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.data.TranscriptionJobDao
import kotlinx.coroutines.flow.Flow

class TranscriptionRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val jobDao: TranscriptionJobDao = db.transcriptionJobDao()
    private val historyDao = db.transcriptionHistoryDao()

    val jobsState: Flow<List<TranscriptionJob>> = jobDao.getAllJobs()

    suspend fun addJob(job: TranscriptionJob): Long {
        return jobDao.insert(job)
    }

    suspend fun updateJob(job: TranscriptionJob) {
        jobDao.update(job)
    }

    suspend fun updateRunning(id: Int, isRunning: Boolean, status: String) {
        jobDao.updateRunning(id, isRunning, status)
    }

    suspend fun updateStatusAndProgress(id: Int, status: String, progress: Float) {
        jobDao.updateStatusAndProgress(id, status, progress)
    }

    suspend fun updateResult(id: Int, transcript: String, language: String, model: String, historyId: Int?, progress: Float, isRunning: Boolean, status: String, error: String?) {
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

    suspend fun succeedSummarization(id: Int, summary: String) {
        jobDao.succeedSummarization(id, summary)
    }

    suspend fun failSummarization(id: Int, error: String?) {
        jobDao.failSummarization(id, error)
    }

    suspend fun claimAutoSummary(id: Int): Boolean {
        return jobDao.claimAutoSummary(id) > 0
    }

    suspend fun claimManualSummary(id: Int): Boolean {
        return jobDao.claimManualSummary(id) > 0
    }

    suspend fun resetStaleSummaries(): Int {
        return jobDao.resetStaleSummaries()
    }

    suspend fun clearJobSummary(jobId: Int) {
        db.withTransaction {
            val job = jobDao.getJobById(jobId) ?: return@withTransaction
            jobDao.updateSummary(jobId, "")
            jobDao.updateSummaryError(jobId, null)
            val hid = job.historyId
            if (hid != null) {
                historyDao.updateSummary(hid, "")
            }
        }
    }

    suspend fun clearJobTranscription(jobId: Int) {
        db.withTransaction {
            val job = jobDao.getJobById(jobId) ?: return@withTransaction
            jobDao.clearTranscriptionResult(jobId)
            val hid = job.historyId
            if (hid != null) {
                historyDao.updateText(hid, "")
            }
        }
    }

    suspend fun updateJobById(jobId: Int, transform: (TranscriptionJob) -> TranscriptionJob) {
        val currentJob = jobDao.getJobById(jobId)
        if (currentJob != null) {
            jobDao.update(transform(currentJob))
        }
    }

    suspend fun getJob(jobId: Int): TranscriptionJob? {
        return jobDao.getJobById(jobId)
    }

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

    suspend fun findHistoryItem(historyId: Int): TranscriptionHistory? {
        return historyDao.getById(historyId)
    }
}
