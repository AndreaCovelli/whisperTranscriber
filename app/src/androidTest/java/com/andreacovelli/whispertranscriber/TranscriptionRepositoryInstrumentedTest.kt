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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
            jobSummarizationDao = db.transcriptionJobSummarizationDao(),
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
}
