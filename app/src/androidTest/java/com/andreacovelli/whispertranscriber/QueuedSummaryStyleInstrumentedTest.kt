package com.andreacovelli.whispertranscriber

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.RoomHistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.queue.JobExecutionBackend
import com.andreacovelli.whispertranscriber.queue.RetryResult
import com.andreacovelli.whispertranscriber.queue.postTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.summary.SummaryStyle
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The automatic-summary style is snapshotted on queued jobs, like the summarizer model. */
@RunWith(AndroidJUnit4::class)
class QueuedSummaryStyleInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repository: TranscriptionRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = TranscriptionRepository(
            db = db,
            jobDao = db.transcriptionJobDao(),
            historyDao = db.transcriptionHistoryDao(),
            historyRepository = RoomHistoryRepository(
                db = db,
                historyDao = db.transcriptionHistoryDao(),
                jobDao = db.transcriptionJobDao(),
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun applyQueuedAutoSummary_updatesTheStyleOfUnfinishedJobs() = runBlocking {
        val jobId = repository.addJob(TranscriptionJob(uri = "content://test/a.wav", displayName = "a.wav")).toInt()
        val settings = SummarizationSettings(
            provider = TranscriptionProvider.GEMINI_FLASH,
            customModelName = "",
            style = SummaryStyle.MEETING_MINUTES,
        )

        repository.applyQueuedAutoSummary(settings)

        assertEquals(settings, repository.getJob(jobId)?.postTranscriptionWorkSpec()?.summarizationSettings)
    }

    @Test
    fun retryJob_replacesTheQueuedStyle() = runBlocking {
        val audio = File.createTempFile("retry-style", ".wav", context.cacheDir).apply { writeText("audio") }
        try {
            val jobId = repository.addJob(
                TranscriptionJob(
                    uri = "content://test/retry.wav",
                    displayName = "retry.wav",
                    stagedFilePath = audio.path,
                    status = JOB_STATUS_ERROR,
                    queuedSummaryStyle = SummaryStyle.BULLET_POINTS.name,
                ),
            ).toInt()

            val result = repository.retryJob(
                jobId,
                TranscriptionProvider.GEMINI_FLASH.name,
                null,
                JobExecutionBackend.WORK_MANAGER.name,
                summarizeAfterTranscription = true,
                notifyResult = false,
                queuedSummarizerProviderName = TranscriptionProvider.GEMINI_FLASH.name,
                queuedSummarizerCustomModelName = "",
                queuedSummaryStyle = SummaryStyle.CUSTOM.name,
                queuedSummaryCustomPrompt = "Only decisions",
            )

            assertEquals(RetryResult.ACCEPTED, result)
            val retried = requireNotNull(repository.getJob(jobId))
            assertEquals(JOB_STATUS_QUEUED, retried.status)
            assertEquals(SummaryStyle.CUSTOM.name, retried.queuedSummaryStyle)
            assertEquals("Only decisions", retried.queuedSummaryCustomPrompt)
        } finally {
            audio.delete()
        }
    }
}
