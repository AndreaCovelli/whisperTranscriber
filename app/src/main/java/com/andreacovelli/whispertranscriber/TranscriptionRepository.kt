package com.andreacovelli.whispertranscriber

import android.content.Context
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.data.TranscriptionJobDao
import kotlinx.coroutines.flow.Flow

class TranscriptionRepository(context: Context) {
    private val jobDao: TranscriptionJobDao = AppDatabase.getDatabase(context).transcriptionJobDao()

    val jobsState: Flow<List<TranscriptionJob>> = jobDao.getAllJobs()

    suspend fun addJob(job: TranscriptionJob): Long {
        return jobDao.insert(job)
    }

    suspend fun updateJob(job: TranscriptionJob) {
        jobDao.update(job)
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

    suspend fun clearCompletedJobs() {
        jobDao.clearCompleted()
    }
}
