package com.andreacovelli.whispertranscriber

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
        historyDao.updateSummary(historyId, "Refined summary")

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
        context.deleteDatabase(databaseName)
        createVersion7Database(databaseName)

        AppDatabase.buildTestDatabase(context, databaseName).use { migratedDb ->
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
            historyDao.updateSummary(historyId, "Retrospective summary")

            assertFalse(ftsMatches(migratedDb, "Planning*"))
            assertTrue(ftsMatches(migratedDb, "Retrospective*"))

            historyDao.deleteById(historyId)

            assertFalse(ftsMatches(migratedDb, "Retrospective*"))
        }

        context.deleteDatabase(databaseName)
    }

    private fun ftsMatches(query: String): Boolean {
        return ftsMatches(db, query)
    }

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

    private fun createVersion7Database(databaseName: String) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `transcription_history` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `text` TEXT NOT NULL,
                                `language` TEXT NOT NULL,
                                `model` TEXT NOT NULL,
                                `summary` TEXT NOT NULL,
                                `displayName` TEXT NOT NULL,
                                `timestamp` INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `active_jobs` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `uri` TEXT NOT NULL,
                                `displayName` TEXT NOT NULL,
                                `sourceMimeType` TEXT,
                                `stagedFilePath` TEXT,
                                `providerName` TEXT NOT NULL DEFAULT 'ELEVEN_LABS',
                                `requestedModel` TEXT,
                                `status` TEXT NOT NULL,
                                `progress` REAL NOT NULL,
                                `transcript` TEXT NOT NULL,
                                `language` TEXT NOT NULL,
                                `model` TEXT NOT NULL,
                                `summary` TEXT NOT NULL,
                                `error` TEXT,
                                `summaryError` TEXT,
                                `isRunning` INTEGER NOT NULL,
                                `isSummarizing` INTEGER NOT NULL,
                                `historyId` INTEGER,
                                `timestamp` INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase.close()
    }
}
