package com.andreacovelli.whispertranscriber

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.HistoryJobCleanup
import com.andreacovelli.whispertranscriber.data.RoomHistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.queue.JobExecutionBackend
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.summary.TranscriptionSummarizationRepository
import com.andreacovelli.whispertranscriber.transcription.CompletedJobCleanup
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CLEARED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_COMPLETED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RETRY_SCHEDULED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_GENERATED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_SUMMARIZING
import com.andreacovelli.whispertranscriber.transcription.TranscriptionData
import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        historyRepository = RoomHistoryRepository(
            db = db,
            historyDao = db.transcriptionHistoryDao(),
            jobDao = db.transcriptionJobDao(),
        )
        summarizationRepository = TranscriptionSummarizationRepository(
            db = db,
            jobSummarizationDao = db.transcriptionJobSummarizationDao(),
            historyRepository = historyRepository,
        )
        repository = TranscriptionRepository(
            db = db,
            jobDao = db.transcriptionJobDao(),
            historyDao = db.transcriptionHistoryDao(),
            historyRepository = historyRepository,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deletedHistoryAndTerminalCard_areNotRecreatedByLateSummaryCompletion() = runBlocking {
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
                status = JOB_STATUS_COMPLETED,
                isSummarizing = true,
                summaryAttemptId = "attempt-1",
            ),
        ).toInt()
        historyRepository.deleteById(originalHistoryId)

        val lateSuccess = summarizationRepository.succeedSummarization(
            id = jobId,
            attemptId = "attempt-1",
            summary = "New summary",
            summaryProviderName = "GEMINI_FLASH",
            summaryModel = "gemini-2.5-flash",
        )

        assertFalse(lateSuccess)
        assertNull(db.transcriptionHistoryDao().getById(originalHistoryId))
        assertNull(repository.getJob(jobId))
    }

    @Test
    fun deletingHistory_removesRecoveredPostTranscriptionJob() = runBlocking {
        val historyId = historyRepository.insert(
            TranscriptionHistory(
                text = "Durable transcript",
                language = "en",
                model = "gemini-2.5-flash",
                displayName = "recovered.wav",
            ),
        )
        val jobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/recovered.wav",
                displayName = "recovered.wav",
                stagedFilePath = "/tmp/recovered.wav",
                status = JOB_STATUS_QUEUED,
                historyId = historyId,
            ),
        ).toInt()

        val removedJobs = historyRepository.deleteById(historyId)

        assertEquals(listOf(HistoryJobCleanup(jobId, "/tmp/recovered.wav")), removedJobs)
        assertNull(db.transcriptionHistoryDao().getById(historyId))
        assertNull(repository.getJob(jobId))
    }

    @Test
    fun joinedJobProjection_readsTranscriptAndSummaryFromHistory() = runBlocking {
        val historyId = historyRepository.insert(
            TranscriptionHistory(
                text = "Authoritative transcript",
                language = "en",
                model = "gemini-2.5-flash",
                summary = "Authoritative summary",
                displayName = "meeting.wav",
            ),
        )
        val jobId = db.transcriptionJobDao().insert(
            TranscriptionJob(
                uri = "content://test/meeting.wav",
                displayName = "meeting.wav",
                status = JOB_STATUS_COMPLETED,
                transcript = "",
                summary = "",
                historyId = historyId,
                finishedAt = 100,
            ),
        ).toInt()

        val projected = repository.jobsState.first().single { it.id == jobId }

        assertEquals("Authoritative transcript", projected.transcript)
        assertEquals("Authoritative summary", projected.summary)
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
                summaryAttemptId = "attempt-1",
                summaryStatus = SUMMARY_STATUS_SUMMARIZING,
            ),
        ).toInt()

        summarizationRepository.succeedSummarization(
            id = jobId,
            attemptId = "attempt-1",
            summary = "New summary",
            summaryProviderName = "GEMINI_FLASH",
            summaryModel = "gemini-2.5-flash",
        )

        val updatedJob = repository.getJob(jobId)
        val updatedHistory = db.transcriptionHistoryDao().getById(historyId)

        assertEquals("", updatedJob?.summary)
        assertEquals("GEMINI_FLASH", updatedJob?.summaryProviderName)
        assertEquals("gemini-2.5-flash", updatedJob?.summaryModel)
        assertEquals(SUMMARY_STATUS_GENERATED, updatedJob?.summaryStatus)
        assertFalse(updatedJob?.isSummarizing ?: true)
        assertEquals("New summary", updatedHistory?.summary)
        assertEquals("GEMINI_FLASH", updatedHistory?.summaryProviderName)
        assertEquals("gemini-2.5-flash", updatedHistory?.summaryModel)
    }

    @Test
    fun executionOwner_rejectsConcurrentClaimsAndStaleCompletionWrites() = runBlocking {
        val jobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/owned.wav",
                displayName = "owned.wav",
            ),
        ).toInt()

        assertNotNull(repository.claimJobForExecution(jobId, "owner-1", "Starting"))
        assertNull(repository.claimJobForExecution(jobId, "owner-2", "Starting"))
        assertFalse(repository.updateStatus(jobId, "owner-2", "Stale status"))
        assertFalse(
            repository.persistCompletedTranscription(
                jobId,
                "owner-2",
                "owned.wav",
                TranscriptionData("Stale transcript", "en", "model"),
            ),
        )
        assertFalse(db.transcriptionHistoryDao().hasHistory())

        assertTrue(
            repository.persistCompletedTranscription(
                jobId,
                "owner-1",
                "owned.wav",
                TranscriptionData("Durable transcript", "en", "model"),
            ),
        )
        assertEquals("", repository.getJob(jobId)?.transcript)
        assertEquals("Durable transcript", repository.getTranscriptForJob(jobId))
        assertNull(repository.claimJobForExecution(jobId, "owner-1", "Duplicate"))
    }

    @Test
    fun markRetryScheduled_keepsRetryStateBlockingSequentialQueue() = runBlocking {
        val jobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/retry.wav",
                displayName = "retry.wav",
                status = JOB_STATUS_QUEUED,
                isRunning = false,
            ),
        ).toInt()

        val ownerId = "owner-1"
        assertNotNull(repository.claimJobForExecution(jobId, ownerId, "Starting..."))
        repository.markRetryScheduled(jobId, ownerId)

        val updatedJob = repository.getJob(jobId)

        assertEquals(JOB_STATUS_RETRY_SCHEDULED, updatedJob?.status)
        assertFalse(updatedJob?.isRunning ?: true)
        assertEquals(1, updatedJob?.retryCount)
        assertEquals(1, repository.countBlockingJobs())
    }

    @Test
    fun orphanRecovery_requeuesOnlyTheMatchingExecutionOwner() = runBlocking {
        val staleJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/stale.wav",
                displayName = "stale.wav",
                status = "Processing...",
                isRunning = true,
                executionOwnerId = "owner-1",
            ),
        ).toInt()
        val retryScheduledJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/retry-scheduled.wav",
                displayName = "retry-scheduled.wav",
                status = JOB_STATUS_RETRY_SCHEDULED,
                isRunning = false,
                executionOwnerId = "owner-2",
            ),
        ).toInt()
        val queuedJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/queued.wav",
                displayName = "queued.wav",
                status = JOB_STATUS_QUEUED,
                isRunning = false,
            ),
        ).toInt()

        val wrongOwnerReset = repository.requeueOrphanedExecution(staleJobId, "owner-2")
        val staleReset = repository.requeueOrphanedExecution(staleJobId, "owner-1")
        val retryReset = repository.requeueOrphanedExecution(retryScheduledJobId, "owner-2")
        val staleJob = repository.getJob(staleJobId)
        val retryScheduledJob = repository.getJob(retryScheduledJobId)
        val untouchedQueuedJob = repository.getJob(queuedJobId)

        assertFalse(wrongOwnerReset)
        assertTrue(staleReset)
        assertTrue(retryReset)
        assertEquals(JOB_STATUS_QUEUED, staleJob?.status)
        assertFalse(staleJob?.isRunning ?: true)
        assertEquals(JOB_STATUS_QUEUED, retryScheduledJob?.status)
        assertFalse(retryScheduledJob?.isRunning ?: true)
        assertEquals(JOB_STATUS_QUEUED, untouchedQueuedJob?.status)
        assertFalse(untouchedQueuedJob?.isRunning ?: true)
        assertEquals(0, repository.countBlockingJobs())
        assertNotNull(repository.claimJobForExecution(staleJobId, "new-owner-1", "Starting..."))
        assertNotNull(repository.claimJobForExecution(retryScheduledJobId, "new-owner-2", "Starting..."))
    }

    @Test
    fun orphanRecovery_invalidatesInterruptedBackgroundSummaryAttempt() = runBlocking {
        val jobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/orphaned-summary.wav",
                displayName = "orphaned-summary.wav",
                status = JOB_STATUS_COMPLETED,
                isRunning = true,
                isSummarizing = true,
                autoSummaryAttempted = true,
                executionOwnerId = "owner-1",
                summaryAttemptId = "attempt-1",
                summaryStatus = SUMMARY_STATUS_SUMMARIZING,
            ),
        ).toInt()

        assertTrue(repository.requeueOrphanedExecution(jobId, "owner-1"))

        val recovered = repository.getJob(jobId)
        assertEquals(JOB_STATUS_QUEUED, recovered?.status)
        assertFalse(recovered?.isRunning ?: true)
        assertFalse(recovered?.isSummarizing ?: true)
        assertEquals("", recovered?.executionOwnerId)
        assertEquals("", recovered?.summaryAttemptId)
        assertEquals(SUMMARY_STATUS_CANCELLED, recovered?.summaryStatus)
        assertFalse(recovered?.autoSummaryAttempted ?: true)
    }

    @Test
    fun retryJob_resetsRetryCountToZero() = runBlocking {
        val jobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/retry-reset.wav",
                displayName = "retry-reset.wav",
                status = JOB_STATUS_RETRY_SCHEDULED,
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
        assertEquals(JOB_STATUS_QUEUED, updatedJob?.status)
        assertEquals(0, updatedJob?.retryCount)
    }

    @Test
    fun retryJob_unlinksOldHistorySoProviderExecutionRunsAgain() = runBlocking {
        val historyId = historyRepository.insert(
            TranscriptionHistory(
                text = "Old transcript",
                language = "en",
                displayName = "retry-history.wav",
            ),
        )
        val jobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/retry-history.wav",
                displayName = "retry-history.wav",
                status = JOB_STATUS_COMPLETED,
                historyId = historyId,
            ),
        ).toInt()

        repository.retryJob(
            jobId = jobId,
            providerName = TranscriptionProvider.GEMINI_FLASH.name,
            requestedModel = TranscriptionProvider.GEMINI_FLASH.modelName,
            executionBackend = JobExecutionBackend.WORK_MANAGER.name,
            summarizeAfterTranscription = false,
            notifyResult = false,
            queuedSummarizerProviderName = "",
            queuedSummarizerCustomModelName = "",
        )

        assertNull(repository.getJob(jobId)?.historyId)
        assertNotNull(db.transcriptionHistoryDao().getById(historyId))
    }

    @Test
    fun clearJobTranscription_resetsJobStateFields() = runBlocking {
        val jobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/clear.wav",
                displayName = "clear.wav",
                status = JOB_STATUS_COMPLETED,
                progress = 1f,
                transcript = "Transcript text",
                language = "en",
                model = "gemini-2.5-flash",
                summary = "Summary text",
                summaryProviderName = "GEMINI_FLASH",
                summaryModel = "gemini-2.5-flash",
                summaryStatus = SUMMARY_STATUS_GENERATED,
                error = "old error",
                summaryError = "old summary error",
            ),
        ).toInt()

        repository.clearJobTranscription(jobId)

        val updatedJob = repository.getJob(jobId)

        assertEquals(JOB_STATUS_CLEARED, updatedJob?.status)
        assertEquals(0f, updatedJob?.progress)
        assertEquals("", updatedJob?.transcript)
        assertEquals("", updatedJob?.language)
        assertEquals("", updatedJob?.model)
        assertEquals("", updatedJob?.summary)
        assertEquals("", updatedJob?.summaryProviderName)
        assertEquals("", updatedJob?.summaryModel)
        assertEquals("", updatedJob?.summaryStatus)
        assertNull(updatedJob?.error)
        assertNull(updatedJob?.summaryError)
    }

    @Test
    fun clearCompletedJobs_returnsCleanupInfoForDeletedSnapshotOnly() = runBlocking {
        val completedJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/completed.wav",
                displayName = "completed.wav",
                stagedFilePath = "/tmp/completed.wav",
                status = JOB_STATUS_COMPLETED,
                isRunning = false,
            ),
        ).toInt()
        val cancelledJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/cancelled.wav",
                displayName = "cancelled.wav",
                stagedFilePath = "/tmp/cancelled.wav",
                status = JOB_STATUS_CANCELLED,
                isRunning = false,
            ),
        ).toInt()
        val queuedJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/queued.wav",
                displayName = "queued.wav",
                status = JOB_STATUS_QUEUED,
                isRunning = false,
            ),
        ).toInt()
        val errorJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/error.wav",
                displayName = "error.wav",
                status = JOB_STATUS_ERROR,
            ),
        ).toInt()
        val clearedJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/cleared.wav",
                displayName = "cleared.wav",
                status = JOB_STATUS_CLEARED,
            ),
        ).toInt()
        val runningTerminalId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/running-terminal.wav",
                displayName = "running-terminal.wav",
                status = JOB_STATUS_COMPLETED,
                isRunning = true,
                executionOwnerId = "owner",
            ),
        ).toInt()
        val summarizingTerminalId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/summarizing-terminal.wav",
                displayName = "summarizing-terminal.wav",
                status = JOB_STATUS_COMPLETED,
                isSummarizing = true,
                summaryAttemptId = "attempt",
            ),
        ).toInt()
        val retryJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/retry.wav",
                displayName = "retry.wav",
                status = JOB_STATUS_RETRY_SCHEDULED,
            ),
        ).toInt()

        val clearedJobs = repository.clearCompletedJobs()

        assertEquals(
            listOf(
                CompletedJobCleanup(jobId = completedJobId, stagedFilePath = "/tmp/completed.wav"),
                CompletedJobCleanup(jobId = cancelledJobId, stagedFilePath = "/tmp/cancelled.wav"),
                CompletedJobCleanup(jobId = errorJobId, stagedFilePath = null),
                CompletedJobCleanup(jobId = clearedJobId, stagedFilePath = null),
            ),
            clearedJobs.sortedBy(CompletedJobCleanup::jobId),
        )
        assertEquals(queuedJobId, repository.getJob(queuedJobId)?.id)
        assertEquals(retryJobId, repository.getJob(retryJobId)?.id)
        assertEquals(runningTerminalId, repository.getJob(runningTerminalId)?.id)
        assertEquals(summarizingTerminalId, repository.getJob(summarizingTerminalId)?.id)
        assertNull(repository.getJob(completedJobId))
        assertNull(repository.getJob(cancelledJobId))
    }

    @Test
    fun terminalRetention_removesOldCardButPreservesHistory() = runBlocking {
        val now = 40L * 24L * 60L * 60L * 1_000L
        val historyId = historyRepository.insert(
            TranscriptionHistory(
                text = "Keep this transcript",
                language = "en",
                displayName = "old.wav",
            ),
        )
        val jobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/old.wav",
                displayName = "old.wav",
                status = JOB_STATUS_COMPLETED,
                historyId = historyId,
                finishedAt = 1,
            ),
        ).toInt()

        assertEquals(listOf(jobId), repository.deleteExpiredTerminalCards(now).map { it.jobId })
        assertNull(repository.getJob(jobId))
        assertNotNull(db.transcriptionHistoryDao().getById(historyId))
    }

    @Test
    fun mainJobProjection_limitsTerminalCardsButIncludesEveryNonterminalJob() = runBlocking {
        db.transcriptionJobDao().insertAll(
            (1..105).map { index ->
                TranscriptionJob(
                    uri = "content://test/terminal-$index.wav",
                    displayName = "terminal-$index.wav",
                    status = JOB_STATUS_COMPLETED,
                    finishedAt = index.toLong(),
                )
            } + TranscriptionJob(
                uri = "content://test/queued.wav",
                displayName = "queued.wav",
                status = JOB_STATUS_QUEUED,
            ),
        )

        val projected = repository.jobsState.first()

        assertEquals(101, projected.size)
        assertTrue(projected.any { it.status == JOB_STATUS_QUEUED })
        assertEquals(100, projected.count { it.status == JOB_STATUS_COMPLETED })
    }

    @Test
    fun addingJobsBeyondQueueCapacity_isRejectedAtomically() = runBlocking {
        val jobs = (1..50).map { index ->
            TranscriptionJob(
                uri = "content://test/$index.wav",
                displayName = "$index.wav",
            )
        }
        repository.addJobsWithinCapacity(jobs)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.addJobsWithinCapacity(
                    listOf(
                        TranscriptionJob(
                            uri = "content://test/overflow.wav",
                            displayName = "overflow.wav",
                        ),
                    ),
                )
            }
        }
        assertEquals(50, db.transcriptionJobDao().countNonterminalJobs())
    }
}
