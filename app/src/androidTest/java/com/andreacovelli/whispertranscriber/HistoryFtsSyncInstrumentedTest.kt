package com.andreacovelli.whispertranscriber

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_COMPLETED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RETRY_SCHEDULED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_SUMMARIZING
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
        historyDao.updateSummary(historyId, "Refined summary", "GEMINI_FLASH", "gemini-2.5-flash")

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
            historyDao.updateSummary(historyId, "Retrospective summary", "GEMINI_PRO", "gemini-2.5-pro")

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
            migratedDb.openHelper.readableDatabase.query(
                "SELECT summaryModel FROM transcription_history",
            ).use { cursor ->
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

    @Test
    fun migrationToVersion13_addsExecutionBackendAndQueuedFollowUpColumns() {
        val databaseName = "history-queued-execution-migration-test.db"
        migrationHelper.createDatabase(databaseName, 12).close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        try {
            migratedDb.openHelper.readableDatabase.query(
                "SELECT executionBackend, summarizeAfterTranscription, notifyResult, " +
                    "queuedSummarizerProviderName, queuedSummarizerCustomModelName FROM active_jobs",
            ).use { cursor ->
                assertEquals(5, cursor.columnCount)
            }
        } finally {
            migratedDb.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationToVersion14_normalizesPersistedJobAndSummaryStatuses() {
        val databaseName = "history-status-normalization-migration-test.db"
        migrationHelper.createDatabase(databaseName, 13).apply {
            execSQL(
                """
                INSERT INTO active_jobs(
                    uri,
                    displayName,
                    providerName,
                    executionBackend,
                    summarizeAfterTranscription,
                    notifyResult,
                    queuedSummarizerProviderName,
                    queuedSummarizerCustomModelName,
                    status,
                    progress,
                    transcript,
                    language,
                    model,
                    summary,
                    summaryProviderName,
                    summaryModel,
                    summaryStatus,
                    isRunning,
                    isSummarizing,
                    autoSummaryAttempted,
                    retryCount,
                    speakerDiarizationEnabled,
                    timestamp
                ) VALUES (
                    'content://test/audio.wav',
                    'audio.wav',
                    'GEMINI_FLASH',
                    'WORK_MANAGER',
                    0,
                    0,
                    '',
                    '',
                    'Completed',
                    1,
                    'Transcript',
                    'en',
                    'gemini-2.5-flash',
                    '',
                    '',
                    '',
                    'Summarizing...',
                    0,
                    1,
                    1,
                    0,
                    0,
                    123456789
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO active_jobs(
                    uri,
                    displayName,
                    providerName,
                    executionBackend,
                    summarizeAfterTranscription,
                    notifyResult,
                    queuedSummarizerProviderName,
                    queuedSummarizerCustomModelName,
                    status,
                    progress,
                    transcript,
                    language,
                    model,
                    summary,
                    summaryProviderName,
                    summaryModel,
                    summaryStatus,
                    isRunning,
                    isSummarizing,
                    autoSummaryAttempted,
                    retryCount,
                    speakerDiarizationEnabled,
                    timestamp
                ) VALUES (
                    'content://test/audio-2.wav',
                    'audio-2.wav',
                    'GEMINI_FLASH',
                    'WORK_MANAGER',
                    0,
                    0,
                    '',
                    '',
                    'Cancelled',
                    0,
                    '',
                    '',
                    '',
                    '',
                    '',
                    '',
                    'Error',
                    0,
                    0,
                    0,
                    0,
                    0,
                    223456789
                )
                """.trimIndent(),
            )
            close()
        }

        val migratedDb = migrationHelper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            *AppDatabase.MIGRATIONS,
        )
        try {
            migratedDb.query(
                "SELECT status, summaryStatus FROM active_jobs ORDER BY timestamp",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(JOB_STATUS_COMPLETED, cursor.getString(0))
                assertEquals(SUMMARY_STATUS_SUMMARIZING, cursor.getString(1))
                assertTrue(cursor.moveToNext())
                assertEquals(JOB_STATUS_CANCELLED, cursor.getString(0))
                assertEquals(SUMMARY_STATUS_ERROR, cursor.getString(1))
            }
        } finally {
            migratedDb.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationToVersion15_recoversInterruptedExecutionAndInvalidatesSummaries() {
        val databaseName = "durable-owner-migration-test.db"
        migrationHelper.createDatabase(databaseName, 14).apply {
            execSQL(
                """
                INSERT INTO transcription_history(
                    id, text, language, model, summary, summaryProviderName, summaryModel, displayName, timestamp
                ) VALUES (1, 'Durable transcript', 'en', 'model', '', '', '', 'completed.wav', 300)
                """.trimIndent(),
            )
            insertVersion14Job(
                displayName = "running.wav",
                status = "Processing",
                isRunning = true,
                timestamp = 100,
            )
            insertVersion14Job(
                displayName = "retry.wav",
                status = JOB_STATUS_RETRY_SCHEDULED,
                timestamp = 200,
            )
            insertVersion14Job(
                displayName = "completed.wav",
                status = "Processing",
                isRunning = true,
                isSummarizing = true,
                autoSummaryAttempted = true,
                summaryStatus = SUMMARY_STATUS_SUMMARIZING,
                historyId = 1,
                timestamp = 300,
            )
            close()
        }

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        try {
            migratedDb.openHelper.readableDatabase.query(
                """
                SELECT status, isRunning, isSummarizing, autoSummaryAttempted,
                       executionOwnerId, summaryAttemptId, summaryStatus, finishedAt
                FROM active_jobs
                ORDER BY timestamp
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(JOB_STATUS_QUEUED, cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getLong(7))

                assertTrue(cursor.moveToNext())
                assertEquals(JOB_STATUS_QUEUED, cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getLong(7))

                assertTrue(cursor.moveToNext())
                assertEquals(JOB_STATUS_COMPLETED, cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals("", cursor.getString(4))
                assertEquals("", cursor.getString(5))
                assertEquals(SUMMARY_STATUS_CANCELLED, cursor.getString(6))
                assertEquals(300, cursor.getLong(7))
            }
        } finally {
            migratedDb.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun everyExportedSchemaUpgradesToVersion15() {
        (7..14).forEach { version ->
            val databaseName = "exported-schema-$version-to-15.db"
            migrationHelper.createDatabase(databaseName, version).close()
            openMigratedDatabase(databaseName).close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationFromVersion6_assignsCurrentMicrosoftProviderToExistingJobs() {
        val databaseName = "provider-default-migration-test.db"
        createLegacyRawDatabase(databaseName, 6) { legacyDb ->
            legacyDb.execSQL(
                """
                INSERT INTO active_jobs(
                    uri,
                    displayName,
                    status,
                    progress,
                    transcript,
                    language,
                    model,
                    summary,
                    isRunning,
                    isSummarizing,
                    timestamp
                ) VALUES (
                    'content://test/queued.wav',
                    'queued.wav',
                    'Queued',
                    0,
                    '',
                    '',
                    '',
                    '',
                    0,
                    0,
                    100
                )
                """.trimIndent(),
            )
        }

        val migratedDb = openMigratedDatabase(databaseName)
        try {
            migratedDb.openHelper.readableDatabase.query(
                "SELECT providerName FROM active_jobs WHERE displayName = 'queued.wav'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE.name, cursor.getString(0))
            }
        } finally {
            migratedDb.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun everyLegacyRawSchemaUpgradesToVersion15() {
        (1..6).forEach { version ->
            val databaseName = "raw-schema-$version-to-15.db"
            createLegacyRawDatabase(databaseName, version)
            openMigratedDatabase(databaseName).close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun ftsMatches(query: String): Boolean = ftsMatches(db, query)

    private fun createLegacyRawDatabase(
        databaseName: String,
        version: Int,
        seed: (SupportSQLiteDatabase) -> Unit = {},
    ) {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS transcription_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        text TEXT NOT NULL,
                        language TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                AppDatabase.MIGRATIONS
                    .filter { it.endVersion <= version }
                    .forEach { it.migrate(db) }
                seed(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(callback)
            .build()
        factory.create(configuration).apply {
            writableDatabase
            close()
        }
    }

    private fun openMigratedDatabase(databaseName: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
            .also { it.openHelper.writableDatabase }

    private fun SupportSQLiteDatabase.insertVersion14Job(
        displayName: String,
        status: String,
        isRunning: Boolean = false,
        isSummarizing: Boolean = false,
        autoSummaryAttempted: Boolean = false,
        summaryStatus: String = "",
        historyId: Int? = null,
        timestamp: Long,
    ) {
        execSQL(
            """
            INSERT INTO active_jobs(
                uri,
                displayName,
                providerName,
                executionBackend,
                summarizeAfterTranscription,
                notifyResult,
                queuedSummarizerProviderName,
                queuedSummarizerCustomModelName,
                status,
                progress,
                transcript,
                language,
                model,
                summary,
                summaryProviderName,
                summaryModel,
                summaryStatus,
                isRunning,
                isSummarizing,
                autoSummaryAttempted,
                retryCount,
                speakerDiarizationEnabled,
                historyId,
                timestamp
            ) VALUES (?, ?, 'GEMINI_FLASH', 'WORK_MANAGER', 0, 0, '', '', ?, 0, '', '', '', '', '', '', ?,
                ?, ?, ?, 0, 0, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "content://test/$displayName",
                displayName,
                status,
                summaryStatus,
                if (isRunning) 1 else 0,
                if (isSummarizing) 1 else 0,
                if (autoSummaryAttempted) 1 else 0,
                historyId,
                timestamp,
            ),
        )
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
}
