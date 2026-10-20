package com.andreacovelli.whispertranscriber.background

import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.StoredMessages
import com.andreacovelli.whispertranscriber.TranscriptionWorker
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.queue.backgroundShareSchedulerJobId
import com.andreacovelli.whispertranscriber.services.SummarizationResult
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryWorkerTest {
    @Test
    fun enqueuedInput_summarizesTheJobWithTheRequestedSettings() = runBlocking {
        val settings = SummarizationSettings(TranscriptionProvider.GEMINI_CUSTOM, "models/custom")
        val calls = mutableListOf<Pair<Int, SummarizationSettings>>()

        runSummaryWork(summaryWorkInput(42, "meeting.m4a", settings)) { jobId, requested ->
            calls += jobId to requested
            SummarizationResult(statusMessage = null)
        }

        assertEquals(listOf(42 to settings), calls)
    }

    @Test
    fun statusMessage_isReturnedEncodedInTheWorkOutput() = runBlocking {
        val status = UserMessage(R.string.summarization_status_generated)

        val result = runSummaryWork(summaryWorkInput(7, "audio.wav", geminiFlash)) { _, _ ->
            SummarizationResult(statusMessage = status)
        }

        assertTrue(result is ListenableWorker.Result.Success)
        val stored = result.outputData.getString(SummaryWorker.STATUS_MESSAGE_OUTPUT)
        assertEquals(status, StoredMessages.decode(stored))
    }

    @Test
    fun summaryFailure_stillCompletesTheWorkBecauseTheErrorIsStoredOnTheJob() = runBlocking {
        val result = runSummaryWork(summaryWorkInput(7, "audio.wav", geminiFlash)) { _, _ ->
            SummarizationResult(statusMessage = null, errorMessage = UserMessage(R.string.provider_request_failed))
        }

        assertTrue(result is ListenableWorker.Result.Success)
        assertNull(result.outputData.getString(SummaryWorker.STATUS_MESSAGE_OUTPUT))
    }

    @Test
    fun missingJobId_failsWithoutSummarizing() = runBlocking {
        var summarized = false

        val result = runSummaryWork(workDataOf(SummaryWorker.PROVIDER_INPUT to "GEMINI_PRO")) { _, _ ->
            summarized = true
            SummarizationResult(statusMessage = null)
        }

        assertEquals(ListenableWorker.Result.failure(), result)
        assertFalse(summarized)
    }

    @Test
    fun unknownProvider_fallsBackToGeminiFlash() = runBlocking {
        var requested: SummarizationSettings? = null
        val input = workDataOf(SummaryWorker.JOB_ID_INPUT to 3, SummaryWorker.PROVIDER_INPUT to "REMOVED_PROVIDER")

        runSummaryWork(input) { _, settings ->
            requested = settings
            SummarizationResult(statusMessage = null)
        }

        assertEquals(SummarizationSettings(TranscriptionProvider.GEMINI_FLASH, ""), requested)
    }

    @Test
    fun notificationIds_areDistinctPerJobAndClearOfOtherForegroundNotifications() {
        val jobIds = listOf(0, 1, 2, 1_001, 60_000, 200_000, Int.MAX_VALUE)
        val ids = jobIds.map(::summaryNotificationId)

        assertEquals(jobIds.size, ids.toSet().size)
        ids.forEach { id ->
            assertTrue(id < 0)
            assertTrue(id != TranscriptionWorker.NOTIFICATION_ID)
        }
        jobIds.filter { it <= Int.MAX_VALUE - 200_000 }.forEach { jobId ->
            assertTrue(summaryNotificationId(jobId) != backgroundShareSchedulerJobId(jobId))
        }
    }

    private companion object {
        val geminiFlash = SummarizationSettings(TranscriptionProvider.GEMINI_FLASH, "")
    }
}
