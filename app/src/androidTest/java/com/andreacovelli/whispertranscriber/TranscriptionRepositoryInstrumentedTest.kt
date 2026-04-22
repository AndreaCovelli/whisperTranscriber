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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptionRepositoryInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: TranscriptionRepository
    private lateinit var historyRepository: RoomHistoryRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        historyRepository = RoomHistoryRepository(db.transcriptionHistoryDao())
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
    fun markRetryScheduled_usesBlockingRetryLiteral() = runBlocking {
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
        assertEquals(1, repository.countBlockingJobs())
    }

    @Test
    fun resetStaleJobs_requeuesRunningJobsAndMakesThemClaimableAgain() = runBlocking {
        val staleJobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/stale.wav",
                displayName = "stale.wav",
                status = "Processing...",
                isRunning = true,
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
        val untouchedQueuedJob = repository.getJob(queuedJobId)

        assertEquals(1, resetCount)
        assertEquals("Queued", staleJob?.status)
        assertFalse(staleJob?.isRunning ?: true)
        assertEquals("Queued", untouchedQueuedJob?.status)
        assertFalse(untouchedQueuedJob?.isRunning ?: true)
        assertEquals(0, repository.countBlockingJobs())
        assertTrue(repository.claimJobForExecution(staleJobId, "Starting..."))
    }
}
