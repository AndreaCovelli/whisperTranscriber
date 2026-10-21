package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_COMPLETED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RETRY_SCHEDULED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RUNNING
import com.andreacovelli.whispertranscriber.ui.main.JobRevealRequest
import com.andreacovelli.whispertranscriber.ui.main.MainMsg
import com.andreacovelli.whispertranscriber.ui.main.MainReducer
import com.andreacovelli.whispertranscriber.ui.main.MainUiState
import com.andreacovelli.whispertranscriber.ui.main.TranscriptionJobState
import org.junit.Assert.assertEquals
import org.junit.Test

class MainStatusTest {
    private val queuedStatus = UserMessage.verbatim(QuantityText(R.plurals.main_queued_files, 1))
    private val idleStatus = UserMessage(R.string.main_waiting_for_audios)

    @Test
    fun queuedStatus_resetsToIdleOnceQueuedJobsFinish() {
        val queued = present(MainUiState(status = queuedStatus), job(JOB_STATUS_QUEUED))
        val running = present(queued, job(JOB_STATUS_RUNNING, isRunning = true))
        val completed = present(running, job(JOB_STATUS_COMPLETED))

        assertEquals(queuedStatus, queued.status)
        assertEquals(queuedStatus, running.status)
        assertEquals(idleStatus, completed.status)
    }

    @Test
    fun queuedStatus_staysWhileRetryIsScheduled() {
        val state = present(MainUiState(status = queuedStatus), job(JOB_STATUS_RETRY_SCHEDULED))

        assertEquals(queuedStatus, state.status)
    }

    @Test
    fun queuedStatus_resetsWhenTheJobFails() {
        val state = present(MainUiState(status = queuedStatus), job(JOB_STATUS_ERROR))

        assertEquals(idleStatus, state.status)
    }

    @Test
    fun queuedStatus_staysUntilTheNewJobIsPresented() {
        val awaitingJob = MainUiState(
            status = queuedStatus,
            jobRevealRequest = JobRevealRequest.AwaitingPresentation(token = 1L, jobId = 2, clearWhenAbsent = false),
        )

        val state = present(awaitingJob, job(JOB_STATUS_COMPLETED, id = 1))

        assertEquals(queuedStatus, state.status)
    }

    @Test
    fun otherStatuses_areNotClearedWhenTheQueueIsIdle() {
        val summaryStatus = UserMessage(R.string.summarization_status_generated)

        val state = present(MainUiState(status = summaryStatus), job(JOB_STATUS_COMPLETED))

        assertEquals(summaryStatus, state.status)
    }

    private fun present(state: MainUiState, vararg jobs: TranscriptionJobState): MainUiState =
        MainReducer.reduce(state, MainMsg.JobPresentationChanged(jobs = jobs.toList(), batchProgressState = null))

    private fun job(status: String, id: Int = 1, isRunning: Boolean = false) =
        TranscriptionJobState(id = id, status = status, isRunning = isRunning)
}
