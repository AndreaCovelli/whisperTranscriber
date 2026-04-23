package com.andreacovelli.whispertranscriber

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.RoomHistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptionRepositoryInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: TranscriptionRepository
    private lateinit var historyRepository: RoomHistoryRepository
    private lateinit var summarizationRepository: TranscriptionSummarizationRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        historyRepository = RoomHistoryRepository(db.transcriptionHistoryDao())
        summarizationRepository = TranscriptionSummarizationRepository(
            db = db,
            jobSummarizationDao = db.transcriptionJobSummarizationDao(),
            historyRepository = historyRepository,
        )
        repository = TranscriptionRepository(
            db = db,
            jobDao = db.transcriptionJobDao(),
            historyRepository = historyRepository,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertHistoryForSummarization_reinsertsDeletedHistoryAndUpdatesJobReference() = runBlocking {
        val originalHistoryId = historyRepository.insert(
            TranscriptionHistory(
                text = "Transcript text",
                language = "en",
                model = "whisper-1",
                displayName = "meeting.wav",
            ),
        )
        val jobId = db.transcriptionJobDao().insert(
            TranscriptionJob(
                uri = "content://test/meeting.wav",
                displayName = "meeting.wav",
                transcript = "Transcript text",
                language = "en",
                model = "whisper-1",
                historyId = originalHistoryId,
            ),
        ).toInt()
        historyRepository.deleteById(originalHistoryId)

        val job = repository.getJob(jobId)
        assertNotNull(job)

        val restoredHistoryId = repository.upsertHistoryForSummarization(
            job = requireNotNull(job),
            summary = "New summary",
            summaryProviderName = "GEMINI_FLASH",
            summaryModel = "gemini-2.5-flash",
        )

        val restoredHistory = db.transcriptionHistoryDao().getById(restoredHistoryId)
        val updatedJob = repository.getJob(jobId)

        assertNotEquals(originalHistoryId, restoredHistoryId)
        assertNotNull(restoredHistory)
        assertEquals("Transcript text", restoredHistory?.text)
        assertEquals("New summary", restoredHistory?.summary)
        assertEquals("GEMINI_FLASH", restoredHistory?.summaryProviderName)
        assertEquals("gemini-2.5-flash", restoredHistory?.summaryModel)
        assertEquals(restoredHistoryId, updatedJob?.historyId)
    }

    @Test
    fun upsertHistoryForSummarization_usesLatestHistoryIdFromDatabase() = runBlocking {
        val jobId = db.transcriptionJobDao().insert(
            TranscriptionJob(
                uri = "content://test/latest-history.wav",
                displayName = "latest-history.wav",
                transcript = "Transcript text",
                language = "en",
                model = "whisper-1",
            ),
        ).toInt()
        val staleJobSnapshot = requireNotNull(repository.getJob(jobId))
        val currentHistoryId = historyRepository.insert(
            TranscriptionHistory(
                text = "Transcript text",
                language = "en",
                model = "whisper-1",
                displayName = "latest-history.wav",
            ),
        )
        db.transcriptionJobDao().updateHistoryId(jobId, currentHistoryId)

        val updatedHistoryId = repository.upsertHistoryForSummarization(
            job = staleJobSnapshot,
            summary = "New summary",
            summaryProviderName = "GEMINI_FLASH",
            summaryModel = "gemini-2.5-flash",
        )

        val updatedHistory = db.transcriptionHistoryDao().getById(currentHistoryId)
        val updatedJob = repository.getJob(jobId)

        assertEquals(currentHistoryId, updatedHistoryId)
        assertEquals("New summary", updatedHistory?.summary)
        assertEquals("GEMINI_FLASH", updatedHistory?.summaryProviderName)
        assertEquals("gemini-2.5-flash", updatedHistory?.summaryModel)
        assertEquals(currentHistoryId, updatedJob?.historyId)
    }

    @Test
    fun succeedSummarization_updatesJobAndHistorySummary() = runBlocking {
        val historyId = historyRepository.insert(
            TranscriptionHistory(
                text = "Transcript text",
                language = "en",
                model = "whisper-1",
                displayName = "meeting.wav",
            ),
        )
        val jobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/meeting.wav",
                displayName = "meeting.wav",
                transcript = "Transcript text",
                language = "en",
                model = "whisper-1",
                historyId = historyId,
                isSummarizing = true,
                summaryStatus = "Summarizing...",
            ),
        ).toInt()

        summarizationRepository.succeedSummarization(
            id = jobId,
            summary = "New summary",
            summaryProviderName = "GEMINI_FLASH",
            summaryModel = "gemini-2.5-flash",
        )

        val updatedJob = repository.getJob(jobId)
        val updatedHistory = db.transcriptionHistoryDao().getById(historyId)

        assertEquals("New summary", updatedJob?.summary)
        assertEquals("GEMINI_FLASH", updatedJob?.summaryProviderName)
        assertEquals("gemini-2.5-flash", updatedJob?.summaryModel)
        assertEquals("Summary generated", updatedJob?.summaryStatus)
        assertFalse(updatedJob?.isSummarizing ?: true)
        assertEquals("New summary", updatedHistory?.summary)
        assertEquals("GEMINI_FLASH", updatedHistory?.summaryProviderName)
        assertEquals("gemini-2.5-flash", updatedHistory?.summaryModel)
    }

    @Test
    fun markRetryScheduled_keepsRetryStateBlockingSequentialQueue() = runBlocking {
        val jobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/retry.wav",
                displayName = "retry.wav",
                status = "Starting...",
                isRunning = true,
            ),
        ).toInt()

        repository.markRetryScheduled(jobId)

        val updatedJob = repository.getJob(jobId)

        assertEquals("Retry scheduled", updatedJob?.status)
        assertFalse(updatedJob?.isRunning ?: true)
        assertEquals(1, updatedJob?.retryCount)
        assertEquals(1, repository.countBlockingJobs())
    }

    @Test
    fun resetStaleJobs_requeuesBlockingJobsAndMakesThemClaimableAgain() = runBlocking {
        val staleJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/stale.wav",
                displayName = "stale.wav",
                status = "Processing...",
                isRunning = true,
            ),
        ).toInt()
        val retryScheduledJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/retry-scheduled.wav",
                displayName = "retry-scheduled.wav",
                status = "Retry scheduled",
                isRunning = false,
            ),
        ).toInt()
        val queuedJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/queued.wav",
                displayName = "queued.wav",
                status = "Queued",
                isRunning = false,
            ),
        ).toInt()

        val resetCount = repository.resetStaleJobs()
        val staleJob = repository.getJob(staleJobId)
        val retryScheduledJob = repository.getJob(retryScheduledJobId)
        val untouchedQueuedJob = repository.getJob(queuedJobId)

        assertEquals(2, resetCount)
        assertEquals("Queued", staleJob?.status)
        assertFalse(staleJob?.isRunning ?: true)
        assertEquals("Queued", retryScheduledJob?.status)
        assertFalse(retryScheduledJob?.isRunning ?: true)
        assertEquals("Queued", untouchedQueuedJob?.status)
        assertFalse(untouchedQueuedJob?.isRunning ?: true)
        assertEquals(0, repository.countBlockingJobs())
        assertNotNull(repository.claimJobForExecution(staleJobId, "Starting..."))
        assertNotNull(repository.claimJobForExecution(retryScheduledJobId, "Starting..."))
    }

    @Test
    fun retryJob_resetsRetryCountToZero() = runBlocking {
        val jobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/retry-reset.wav",
                displayName = "retry-reset.wav",
                status = "Retry scheduled",
                retryCount = 2,
                error = "timeout",
            ),
        ).toInt()

        val retried = repository.retryJob(
            jobId = jobId,
            providerName = TranscriptionProvider.GEMINI_FLASH.name,
            requestedModel = TranscriptionProvider.GEMINI_FLASH.modelName,
            executionBackend = JobExecutionBackend.WORK_MANAGER.name,
            summarizeAfterTranscription = false,
            notifyResult = false,
            queuedSummarizerProviderName = "",
            queuedSummarizerCustomModelName = "",
        )

        val updatedJob = repository.getJob(jobId)

        assertTrue(retried)
        assertEquals("Queued", updatedJob?.status)
        assertEquals(0, updatedJob?.retryCount)
    }

    @Test
    fun clearCompletedJobs_returnsCleanupInfoForDeletedSnapshotOnly() = runBlocking {
        val completedJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/completed.wav",
                displayName = "completed.wav",
                stagedFilePath = "/tmp/completed.wav",
                status = "Completed",
                isRunning = false,
            ),
        ).toInt()
        val cancelledJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/cancelled.wav",
                displayName = "cancelled.wav",
                stagedFilePath = "/tmp/cancelled.wav",
                status = "Cancelled",
                isRunning = false,
            ),
        ).toInt()
        val queuedJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/queued.wav",
                displayName = "queued.wav",
                status = "Queued",
                isRunning = false,
            ),
        ).toInt()

        val clearedJobs = repository.clearCompletedJobs()

        assertEquals(
            listOf(
                CompletedJobCleanup(jobId = completedJobId, stagedFilePath = "/tmp/completed.wav"),
                CompletedJobCleanup(jobId = cancelledJobId, stagedFilePath = "/tmp/cancelled.wav"),
            ),
            clearedJobs.sortedBy(CompletedJobCleanup::jobId),
        )
        assertEquals(queuedJobId, repository.getJob(queuedJobId)?.id)
        assertNull(repository.getJob(completedJobId))
        assertNull(repository.getJob(cancelledJobId))
    }
}
