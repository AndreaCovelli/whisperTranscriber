package com.andreacovelli.whispertranscriber

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RUNNING
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JobMessageMigrationInstrumentedTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "job-message-migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationToVersion16_movesProgressTextOutOfTheStatusAndKeepsStoredTextReadable() {
        migrationHelper.createDatabase(databaseName, 15).apply {
            insertVersion15Job("running.wav", status = "Uploading audio…", timestamp = 100)
            insertVersion15Job(
                "failed.wav",
                status = JOB_STATUS_ERROR,
                error = "Provider rejected the audio.",
                summaryError = "Summary failed.",
                timestamp = 200,
            )
            insertVersion15Job("queued.wav", status = JOB_STATUS_QUEUED, timestamp = 300)
            close()
        }

        migrationHelper.runMigrationsAndValidate(databaseName, 16, true, *AppDatabase.MIGRATIONS).close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val dao = migratedDb.transcriptionJobDao()
            val jobs = runBlocking { (1..3).mapNotNull { id -> dao.getJobById(id) } }.associateBy { it.displayName }

            val running = jobs.getValue("running.wav")
            assertEquals(JOB_STATUS_RUNNING, running.status)
            assertEquals(UserMessage.verbatim("Uploading audio…"), StoredMessages.decode(running.statusMessage))

            val failed = jobs.getValue("failed.wav")
            assertEquals(JOB_STATUS_ERROR, failed.status)
            assertNull(failed.statusMessage)
            assertEquals(UserMessage.verbatim("Provider rejected the audio."), StoredMessages.decode(failed.error))
            assertEquals(UserMessage.verbatim("Summary failed."), StoredMessages.decode(failed.summaryError))

            val queued = jobs.getValue("queued.wav")
            assertEquals(JOB_STATUS_QUEUED, queued.status)
            assertNull(queued.statusMessage)
            assertNull(queued.error)
            assertNull(queued.summaryError)
        } finally {
            migratedDb.close()
        }
    }

    private fun SupportSQLiteDatabase.insertVersion15Job(
        displayName: String,
        status: String,
        error: String? = null,
        summaryError: String? = null,
        timestamp: Long,
    ) {
        execSQL(
            """
            INSERT INTO active_jobs(
                uri, displayName, providerName, executionBackend, summarizeAfterTranscription, notifyResult,
                queuedSummarizerProviderName, queuedSummarizerCustomModelName, status, progress, transcript,
                language, model, summary, summaryProviderName, summaryModel, summaryStatus, isRunning,
                isSummarizing, autoSummaryAttempted, retryCount, speakerDiarizationEnabled, error, summaryError,
                timestamp
            ) VALUES (?, ?, 'GEMINI_FLASH', 'WORK_MANAGER', 0, 0, '', '', ?, 0, '', '', '', '', '', '', '',
                ?, 0, 0, 0, 0, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "content://test/$displayName",
                displayName,
                status,
                if (status == JOB_STATUS_QUEUED || status == JOB_STATUS_ERROR) 0 else 1,
                error,
                summaryError,
                timestamp,
            ),
        )
    }
}
