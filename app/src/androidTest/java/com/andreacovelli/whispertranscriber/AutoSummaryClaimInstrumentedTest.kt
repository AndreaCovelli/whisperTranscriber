package com.andreacovelli.whispertranscriber

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoSummaryClaimInstrumentedTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun claimAutoSummary_onlySucceedsOnceForOwningExecution() = runBlocking {
        val jobDao = db.transcriptionJobDao()
        val summarizationDao = db.transcriptionJobSummarizationDao()
        val jobId = insertJob(testJob().copy(isRunning = true, executionOwnerId = "work-owner"))

        val otherOwnerClaim = summarizationDao.claimBackgroundAutoSummary(
            jobId,
            "other-owner",
            "attempt-0",
            "GEMINI_FLASH",
            "gemini-2.5-flash",
        )
        val firstClaim = summarizationDao.claimBackgroundAutoSummary(
            jobId,
            "work-owner",
            "attempt-1",
            "GEMINI_FLASH",
            "gemini-2.5-flash",
        )
        val secondClaim = summarizationDao.claimBackgroundAutoSummary(
            jobId,
            "work-owner",
            "attempt-2",
            "GEMINI_FLASH",
            "gemini-2.5-flash",
        )
        val storedJob = jobDao.getJobById(jobId)

        assertEquals(0, otherOwnerClaim)
        assertEquals(1, firstClaim)
        assertEquals(0, secondClaim)
        assertTrue(storedJob?.isSummarizing == true)
        assertEquals("attempt-1", storedJob?.summaryAttemptId)
        assertEquals(null, storedJob?.summaryError)
    }

    @Test
    fun updateQueuedAutoSummary_rewritesOnlyJobsThatHaveNotReachedSummarization() = runBlocking {
        val jobDao = db.transcriptionJobDao()
        val finishedId = insertJob(testJob(displayName = "finished.wav"))
        val runningId = jobDao.insert(
            testJob(displayName = "running.wav").copy(isRunning = true, executionOwnerId = "owner"),
        ).toInt()
        val summarizingId = insertJob(
            testJob(displayName = "summarizing.wav", isSummarizing = true, summaryAttemptId = "attempt")
                .copy(isRunning = true, executionOwnerId = "owner"),
        )
        val queuedId = jobDao.insert(testJob(displayName = "queued.wav")).toInt()

        assertEquals(2, jobDao.updateQueuedAutoSummary(true, "GEMINI_CUSTOM", "custom-model"))

        assertFalse(jobDao.getJobById(finishedId)!!.summarizeAfterTranscription)
        assertFalse(jobDao.getJobById(summarizingId)!!.summarizeAfterTranscription)
        listOf(runningId, queuedId).forEach { jobId ->
            val job = jobDao.getJobById(jobId)!!
            assertTrue(job.summarizeAfterTranscription)
            assertEquals("GEMINI_CUSTOM", job.queuedSummarizerProviderName)
            assertEquals("custom-model", job.queuedSummarizerCustomModelName)
        }

        jobDao.updateQueuedAutoSummary(false, "", "")

        assertFalse(jobDao.getJobById(runningId)!!.summarizeAfterTranscription)
        assertFalse(jobDao.getJobById(queuedId)!!.summarizeAfterTranscription)
    }

    @Test
    fun claimManualSummary_allowsResummarizeButStillDeduplicates() = runBlocking {
        val jobDao = db.transcriptionJobDao()
        val summarizationDao = db.transcriptionJobSummarizationDao()
        val jobId = insertJob(testJob(summary = "Existing summary"))

        val firstClaim = summarizationDao.claimManualSummary(
            jobId,
            "attempt-1",
            "GEMINI_FLASH",
            "gemini-2.5-flash",
        )
        val secondClaim = summarizationDao.claimManualSummary(
            jobId,
            "attempt-2",
            "GEMINI_FLASH",
            "gemini-2.5-flash",
        )
        val storedJob = jobDao.getJobById(jobId)

        assertEquals(1, firstClaim)
        assertEquals(0, secondClaim)
        assertTrue(storedJob?.isSummarizing == true)
    }

    @Test
    fun forcedCancellation_invalidatesAttemptAndRejectsLateCompletion() = runBlocking {
        val jobDao = db.transcriptionJobDao()
        val summarizationDao = db.transcriptionJobSummarizationDao()
        val jobId = insertJob(
            testJob(
                isSummarizing = true,
                summaryAttemptId = "attempt-1",
            ),
        )

        val cancelled = summarizationDao.cancelSummarization(jobId)
        val lateSuccess = summarizationDao.succeedSummarization(
            jobId,
            "attempt-1",
            "GEMINI_FLASH",
            "gemini-2.5-flash",
        )
        val storedJob = jobDao.getJobById(jobId)

        assertEquals(1, cancelled)
        assertEquals(0, lateSuccess)
        assertFalse(storedJob?.isSummarizing ?: true)
        assertEquals("", storedJob?.summaryAttemptId)
        assertEquals("", storedJob?.summary)
    }

    @Test
    fun startupRecovery_cancelsOnlyOrphanedUiSummaryAttempts() = runBlocking {
        val jobDao = db.transcriptionJobDao()
        val uiJobId = insertJob(
            testJob(
                displayName = "ui.wav",
                isSummarizing = true,
                summaryAttemptId = "ui-attempt",
            ),
        )
        val backgroundJobId = insertJob(
            testJob(
                displayName = "background.wav",
                isSummarizing = true,
                summaryAttemptId = "background-attempt",
            ).copy(
                isRunning = true,
                executionOwnerId = "work-owner",
            ),
        )

        assertEquals(1, jobDao.cancelOrphanedUiSummaries("new-session:"))

        val uiJob = jobDao.getJobById(uiJobId)
        val backgroundJob = jobDao.getJobById(backgroundJobId)
        assertFalse(uiJob?.isSummarizing ?: true)
        assertEquals("", uiJob?.summaryAttemptId)
        assertEquals("CANCELLED", uiJob?.summaryStatus)
        assertTrue(backgroundJob?.isSummarizing == true)
        assertEquals("background-attempt", backgroundJob?.summaryAttemptId)
    }

    @Test
    fun clearingSummaryState_setsSummaryErrorBackToNull() = runBlocking {
        val jobDao = db.transcriptionJobDao()
        val summarizationDao = db.transcriptionJobSummarizationDao()
        val jobId = insertJob(
            testJob(summary = "Old summary").copy(
                summaryError = "Old error",
            ),
        )

        summarizationDao.clearJobSummaryFields(jobId)

        val storedJob = jobDao.getJobById(jobId)
        assertEquals("", storedJob?.summary)
        assertNull(storedJob?.summaryError)
    }

    private fun testJob(
        displayName: String = "sample.wav",
        summary: String = "",
        isSummarizing: Boolean = false,
        summaryAttemptId: String = "",
    ): TranscriptionJob = TranscriptionJob(
        uri = "content://test/$displayName",
        displayName = displayName,
        transcript = "Transcript text",
        summary = summary,
        isRunning = false,
        isSummarizing = isSummarizing,
        summaryAttemptId = summaryAttemptId,
    )

    private suspend fun insertJob(job: TranscriptionJob): Int {
        val historyId = db.transcriptionHistoryDao().insert(
            TranscriptionHistory(
                text = "Transcript text",
                language = "en",
                displayName = job.displayName,
            ),
        ).toInt()
        return db.transcriptionJobDao().insert(job.copy(historyId = historyId)).toInt()
    }
}
