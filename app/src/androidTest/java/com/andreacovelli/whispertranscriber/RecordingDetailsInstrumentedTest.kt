package com.andreacovelli.whispertranscriber

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.RoomHistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.transcription.RecordingDetails
import com.andreacovelli.whispertranscriber.transcription.TranscriptionData
import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingDetailsInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: TranscriptionRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
    fun persistCompletedTranscription_recordsProviderAndRecordingDetailsInHistory() = runBlocking {
        val history = completeJob(providerName = "GEMINI_FLASH", speakerLabels = true)

        assertEquals("GEMINI_FLASH", history.providerName)
        assertEquals(61_000L, history.durationMs)
        assertEquals(2_048L, history.sourceSizeBytes)
        assertTrue(history.speakerLabels)
    }

    @Test
    fun persistCompletedTranscription_normalizesLegacyProviderNames() = runBlocking {
        val history = completeJob(providerName = "GEMINI_25_FLASH", speakerLabels = false)

        assertEquals("GEMINI_FLASH", history.providerName)
    }

    private suspend fun completeJob(providerName: String, speakerLabels: Boolean) = run {
        val jobId = repository.addJob(
            TranscriptionJob(
                uri = "content://test/details.wav",
                displayName = "details.wav",
                providerName = providerName,
            ),
        ).toInt()
        assertNotNull(repository.claimJobForExecution(jobId, "owner-1", UserMessage.verbatim("Starting")))
        assertTrue(
            repository.persistCompletedTranscription(
                jobId,
                "owner-1",
                "details.wav",
                TranscriptionData("Speaker A: Hi", "en", "model", speakerLabels = speakerLabels),
                RecordingDetails(durationMs = 61_000L, sourceSizeBytes = 2_048L),
            ),
        )
        requireNotNull(db.transcriptionHistoryDao().getById(requireNotNull(repository.getJob(jobId)?.historyId)))
    }
}
