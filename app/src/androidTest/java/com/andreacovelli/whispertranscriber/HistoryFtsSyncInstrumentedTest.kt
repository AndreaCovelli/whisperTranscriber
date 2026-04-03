package com.andreacovelli.whispertranscriber

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryFtsSyncInstrumentedTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private lateinit var db: AppDatabase
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
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
        historyDao.updateSummary(historyId, "Refined summary", "gemini-2.5-flash")

        assertFalse(ftsMatches("Original*"))
        assertTrue(ftsMatches("Updated*"))
        assertTrue(ftsMatches("Refined*"))

        historyDao.deleteById(historyId)

        assertFalse(ftsMatches("Updated*"))
        assertFalse(ftsMatches("meeting*"))
    }

    @Test
    fun migratedDatabaseKeepsFtsIndexInSyncWithHistoryWrites() = runBlocking {
        val databaseName = "history-fts-migration-test.db"
        migrationHelper.createDatabase(databaseName, 8).close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        try {
            val historyDao = migratedDb.transcriptionHistoryDao()
            val historyId = historyDao.insert(
                TranscriptionHistory(
                    text = "Planning notes",
                    language = "en",
                    summary = "Sprint planning",
                    displayName = "planning.wav",
                ),
            ).toInt()

            assertTrue(ftsMatches(migratedDb, "Planning*"))
            assertTrue(ftsMatches(migratedDb, "sprint*"))

            historyDao.updateText(historyId, "Retrospective notes")
            historyDao.updateSummary(historyId, "Retrospective summary", "gemini-2.5-pro")

            assertFalse(ftsMatches(migratedDb, "sprint*"))
            assertTrue(ftsMatches(migratedDb, "Retrospective*"))

            historyDao.deleteById(historyId)

            assertFalse(ftsMatches(migratedDb, "Retrospective*"))
        } finally {
            migratedDb.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationToVersion9_addsSummaryModelAndJobBatchColumnsWithDefaults() {
        val databaseName = "history-summary-model-migration-test.db"
        migrationHelper.createDatabase(databaseName, 8).close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        try {
            migratedDb.openHelper.readableDatabase.query("SELECT summaryModel FROM transcription_history").use { cursor ->
                assertEquals(1, cursor.columnCount)
            }
            migratedDb.openHelper.readableDatabase.query(
                "SELECT summaryModel, batchId, speakerDiarizationEnabled FROM active_jobs",
            ).use { cursor ->
                assertEquals(3, cursor.columnCount)
            }
        } finally {
            migratedDb.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun ftsMatches(query: String): Boolean = ftsMatches(db, query)

    private fun ftsMatches(database: AppDatabase, query: String): Boolean {
        database.openHelper.writableDatabase.query(
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
