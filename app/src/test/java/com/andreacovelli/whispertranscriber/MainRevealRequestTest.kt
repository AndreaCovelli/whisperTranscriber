package com.andreacovelli.whispertranscriber

import android.net.Uri
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_COMPLETED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.ui.main.JobRevealRequest
import com.andreacovelli.whispertranscriber.ui.main.MainMsg
import com.andreacovelli.whispertranscriber.ui.main.MainReducer
import com.andreacovelli.whispertranscriber.ui.main.MainUiState
import com.andreacovelli.whispertranscriber.ui.main.TranscriptionJobState
import com.andreacovelli.whispertranscriber.ui.screens.BatchProgressState
import com.andreacovelli.whispertranscriber.ui.screens.resolveMainJobItemIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainRevealRequestTest {
    @Test
    fun newerShare_supersedesOlderInFlightRevealRequest() {
        val firstShare = MainReducer.reduce(
            MainUiState(),
            MainMsg.SharedAudioReceived(listOf(Uri.EMPTY)),
        )
        val secondShare = MainReducer.reduce(
            firstShare,
            MainMsg.SharedAudioReceived(listOf(Uri.EMPTY)),
        )

        val afterStaleCompletion = MainReducer.reduce(
            secondShare,
            MainMsg.SharedAudioQueueFinished(
                requestToken = requireNotNull(firstShare.jobRevealRequest).token,
                firstCreatedJobId = 10,
            ),
        )

        assertEquals(secondShare.mainNavigationRequest, afterStaleCompletion.mainNavigationRequest)
        assertEquals(secondShare.jobRevealRequest, afterStaleCompletion.jobRevealRequest)
        assertEquals(2L, afterStaleCompletion.latestRevealRequestToken)
    }

    @Test
    fun staleCompletionsAndAcknowledgements_cannotReplaceOrClearNewerRequests() {
        val firstRequest = MainReducer.reduce(
            MainUiState(),
            MainMsg.SharedAudioReceived(listOf(Uri.EMPTY)),
        )
        val firstToken = requireNotNull(firstRequest.jobRevealRequest).token
        val secondRequest = MainReducer.reduce(
            firstRequest,
            MainMsg.SharedAudioReceived(listOf(Uri.EMPTY)),
        )
        val secondToken = requireNotNull(secondRequest.jobRevealRequest).token
        val withSecondJob = MainReducer.reduce(
            secondRequest,
            MainMsg.JobPresentationChanged(
                jobs = listOf(testJob(id = 22)),
                batchProgressState = null,
            ),
        )
        val ready = MainReducer.reduce(
            withSecondJob,
            MainMsg.SharedAudioQueueFinished(secondToken, firstCreatedJobId = 22),
        )

        val afterStaleMessages = listOf(
            MainMsg.SharedAudioQueueFinished(firstToken, firstCreatedJobId = 11),
            MainMsg.MainNavigationHandled(firstToken),
            MainMsg.JobRevealHandled(firstToken),
        ).fold(ready, MainReducer::reduce)

        assertEquals(secondToken, afterStaleMessages.mainNavigationRequest?.token)
        assertEquals(JobRevealRequest.Ready(secondToken, jobId = 22), afterStaleMessages.jobRevealRequest)

        val acknowledged = listOf(
            MainMsg.MainNavigationHandled(secondToken),
            MainMsg.JobRevealHandled(secondToken),
        ).fold(afterStaleMessages, MainReducer::reduce)

        assertNull(acknowledged.mainNavigationRequest)
        assertNull(acknowledged.jobRevealRequest)
    }

    @Test
    fun foregroundReveal_waitsForCreatedJobToAppearInPresentation() {
        val requested = MainReducer.reduce(
            MainUiState(),
            MainMsg.SharedAudioReceived(listOf(Uri.EMPTY)),
        )
        val token = requireNotNull(requested.jobRevealRequest).token

        val queued = MainReducer.reduce(
            requested,
            MainMsg.SharedAudioQueueFinished(token, firstCreatedJobId = 42),
        )
        val presented = MainReducer.reduce(
            queued,
            MainMsg.JobPresentationChanged(
                jobs = listOf(testJob(id = 42)),
                batchProgressState = null,
            ),
        )

        assertEquals(
            JobRevealRequest.AwaitingPresentation(token, jobId = 42, clearWhenAbsent = false),
            queued.jobRevealRequest,
        )
        assertEquals(JobRevealRequest.Ready(token, jobId = 42), presented.jobRevealRequest)
    }

    @Test
    fun missingNotificationTarget_isClearedAfterJobsLoad() {
        val requested = MainReducer.reduce(
            MainUiState(),
            MainMsg.BackgroundShareResultOpened(jobId = 404),
        )

        val loaded = MainReducer.reduce(
            requested,
            MainMsg.JobPresentationChanged(jobs = emptyList(), batchProgressState = null),
        )

        assertEquals(1L, loaded.mainNavigationRequest?.token)
        assertNull(loaded.jobRevealRequest)
    }

    @Test
    fun jobIndex_accountsForBatchProgressAndCompletedJobControls() {
        val queuedJobs = listOf(
            testJob(id = 1),
            testJob(id = 2),
        )
        val jobsWithCompleted = listOf(
            testJob(id = 9, status = JOB_STATUS_COMPLETED),
            testJob(id = 1),
            testJob(id = 2),
        )
        val batchProgress = BatchProgressState(currentFileNumber = 1, totalFiles = 2, completedFiles = 0)

        assertEquals(3, resolveMainJobItemIndex(MainUiState(jobs = queuedJobs), jobId = 2))
        assertEquals(
            4,
            resolveMainJobItemIndex(
                MainUiState(jobs = queuedJobs, batchProgressState = batchProgress),
                jobId = 2,
            ),
        )
        assertEquals(5, resolveMainJobItemIndex(MainUiState(jobs = jobsWithCompleted), jobId = 2))
        assertEquals(
            6,
            resolveMainJobItemIndex(
                MainUiState(jobs = jobsWithCompleted, batchProgressState = batchProgress),
                jobId = 2,
            ),
        )
        assertNull(resolveMainJobItemIndex(MainUiState(jobs = queuedJobs), jobId = 404))
    }

    private fun testJob(id: Int, status: String = JOB_STATUS_QUEUED): TranscriptionJobState = TranscriptionJobState(
        id = id,
        displayName = "audio-$id.wav",
        status = status,
    )
}
