package com.andreacovelli.whispertranscriber

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryFtsSyncInstrumentedTest {
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
    fun ftsIndexStaysInSyncWithHistoryWrites() = runBlocking {
        val historyDao = db.transcriptionHistoryDao()
        val historyId = historyDao.insert(
            TranscriptionHistory(
                text = "Original transcript",
                language = "en",
                summary = "Brief summary",
                displayName = "meeting-note.wav",
            ),
        ).toInt()

        assertTrue(ftsMatches("Original*"))
        assertTrue(ftsMatches("meeting*"))

        historyDao.updateText(historyId, "Updated transcript body")
        historyDao.updateSummary(historyId, "Refined summary")

        assertFalse(ftsMatches("Original*"))
        assertTrue(ftsMatches("Updated*"))
        assertTrue(ftsMatches("Refined*"))

        historyDao.deleteById(historyId)

        assertFalse(ftsMatches("Updated*"))
        assertFalse(ftsMatches("meeting*"))
    }

    private fun ftsMatches(query: String): Boolean {
        db.openHelper.writableDatabase.query(
            SimpleSQLiteQuery(
                """
                SELECT COUNT(*)
                FROM transcription_history_fts
                WHERE transcription_history_fts MATCH ?
                """.trimIndent(),
                arrayOf(query),
            ),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0) > 0
        }
    }
}
