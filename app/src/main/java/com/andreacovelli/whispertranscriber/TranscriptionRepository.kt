package com.andreacovelli.whispertranscriber

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TranscriptionRepository {
    const val MAX_QUEUE_SIZE = 50

    val jobQueue = Channel<TranscriptionJobState>(MAX_QUEUE_SIZE)

    private val _jobsState = MutableStateFlow<List<TranscriptionJobState>>(emptyList())
    val jobsState: StateFlow<List<TranscriptionJobState>> = _jobsState.asStateFlow()

    fun addJob(job: TranscriptionJobState) {
        _jobsState.update { it + job }
    }

    fun updateJob(jobId: String, transform: (TranscriptionJobState) -> TranscriptionJobState) {
        _jobsState.update { state ->
            state.map { if (it.id == jobId) transform(it) else it }
        }
    }

    fun getJob(jobId: String): TranscriptionJobState? {
        return _jobsState.value.find { it.id == jobId }
    }

    fun clearCompletedJobs() {
        _jobsState.update { state ->
            state.filter { job ->
                job.isRunning || (job.transcript.isBlank() && job.error == null)
            }
        }
    }
}
