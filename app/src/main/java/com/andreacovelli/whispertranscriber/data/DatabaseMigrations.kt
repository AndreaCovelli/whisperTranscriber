package com.andreacovelli.whispertranscriber.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.andreacovelli.whispertranscriber.StoredMessages
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CLEARED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_COMPLETED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RETRY_SCHEDULED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RUNNING
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_GENERATED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_SUMMARIZING

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transcription_history ADD COLUMN model TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transcription_history ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transcription_history ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `active_jobs` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `uri` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `progress` REAL NOT NULL,
                `transcript` TEXT NOT NULL,
                `language` TEXT NOT NULL,
                `model` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `error` TEXT,
                `isRunning` INTEGER NOT NULL,
                `isSummarizing` INTEGER NOT NULL,
                `historyId` INTEGER,
                `timestamp` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN summaryError TEXT")
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN sourceMimeType TEXT")
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN stagedFilePath TEXT")
        db.execSQL(
            "ALTER TABLE active_jobs ADD COLUMN providerName TEXT NOT NULL DEFAULT 'MICROSOFT_MAI_TRANSCRIBE'",
        )
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN requestedModel TEXT")
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS `transcription_history_fts`
            USING fts4(
                `text`,
                `summary`,
                `displayName`,
                content=`transcription_history`,
                tokenize=unicode61
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `transcription_history_fts`(`transcription_history_fts`)
            VALUES('rebuild')
            """.trimIndent(),
        )
    }
}

private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transcription_history ADD COLUMN summaryModel TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN summaryModel TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN batchId TEXT")
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN speakerDiarizationEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN summaryStatus TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN autoSummaryAttempted INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transcription_history ADD COLUMN summaryProviderName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN summaryProviderName TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN executionBackend TEXT NOT NULL DEFAULT 'WORK_MANAGER'")
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN summarizeAfterTranscription INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN notifyResult INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN queuedSummarizerProviderName TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "ALTER TABLE active_jobs ADD COLUMN queuedSummarizerCustomModelName TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE active_jobs
            SET status = CASE status
                WHEN 'Queued' THEN '$JOB_STATUS_QUEUED'
                WHEN 'Retry scheduled' THEN '$JOB_STATUS_RETRY_SCHEDULED'
                WHEN 'Completed' THEN '$JOB_STATUS_COMPLETED'
                WHEN 'Cleared' THEN '$JOB_STATUS_CLEARED'
                WHEN 'Error' THEN '$JOB_STATUS_ERROR'
                WHEN 'Cancelled' THEN '$JOB_STATUS_CANCELLED'
                ELSE status
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            UPDATE active_jobs
            SET summaryStatus = CASE summaryStatus
                WHEN 'Summarizing...' THEN '$SUMMARY_STATUS_SUMMARIZING'
                WHEN 'Summary generated' THEN '$SUMMARY_STATUS_GENERATED'
                WHEN 'Error' THEN '$SUMMARY_STATUS_ERROR'
                WHEN 'Cancelled' THEN '$SUMMARY_STATUS_CANCELLED'
                ELSE summaryStatus
            END
            """.trimIndent(),
        )
    }
}

private val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN executionOwnerId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN summaryAttemptId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN finishedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            UPDATE active_jobs
            SET status = CASE
                    WHEN isRunning = 1 AND historyId IS NULL THEN '$JOB_STATUS_QUEUED'
                    WHEN status = '$JOB_STATUS_RETRY_SCHEDULED' THEN '$JOB_STATUS_QUEUED'
                    WHEN isRunning = 1 AND historyId IS NOT NULL THEN '$JOB_STATUS_COMPLETED'
                    ELSE status
                END,
                summaryStatus = CASE
                    WHEN isSummarizing = 1 THEN '$SUMMARY_STATUS_CANCELLED'
                    ELSE summaryStatus
                END,
                autoSummaryAttempted = CASE
                    WHEN isSummarizing = 1 THEN 0
                    ELSE autoSummaryAttempted
                END,
                isRunning = 0,
                isSummarizing = 0,
                executionOwnerId = '',
                summaryAttemptId = '',
                finishedAt = CASE
                    WHEN status IN (
                        '$JOB_STATUS_COMPLETED',
                        '$JOB_STATUS_ERROR',
                        '$JOB_STATUS_CANCELLED',
                        '$JOB_STATUS_CLEARED'
                    ) OR (isRunning = 1 AND historyId IS NOT NULL)
                    THEN timestamp
                    ELSE 0
                END
            """.trimIndent(),
        )
    }
}

// Stored text becomes encoded messages: progress text moves out of status into statusMessage, and
// legacy error text is wrapped as literal text so it is still shown as written.
private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE active_jobs ADD COLUMN statusMessage TEXT")
        val version15Statuses = setOf(
            JOB_STATUS_QUEUED,
            JOB_STATUS_RETRY_SCHEDULED,
            JOB_STATUS_COMPLETED,
            JOB_STATUS_CLEARED,
            JOB_STATUS_ERROR,
            JOB_STATUS_CANCELLED,
        )
        // Read everything first so the table is not changed while its cursor is open.
        val updates = db.query("SELECT id, status, error, summaryError FROM active_jobs").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val values = ContentValues()
                    val status = cursor.getString(1)
                    if (status !in version15Statuses) {
                        values.put("status", JOB_STATUS_RUNNING)
                        values.put("statusMessage", StoredMessages.encodeLiteral(status))
                    }
                    if (!cursor.isNull(2)) values.put("error", StoredMessages.encodeLiteral(cursor.getString(2)))
                    if (!cursor.isNull(3)) {
                        values.put("summaryError", StoredMessages.encodeLiteral(cursor.getString(3)))
                    }
                    if (values.size() > 0) add(cursor.getInt(0) to values)
                }
            }
        }
        updates.forEach { (id, values) ->
            db.update("active_jobs", SQLiteDatabase.CONFLICT_NONE, values, "id = ?", arrayOf<Any>(id))
        }
    }
}

private val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transcription_history_timestamp_id` " +
                "ON `transcription_history` (`timestamp`, `id`)",
        )
    }
}

internal val DATABASE_MIGRATIONS = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_13,
    MIGRATION_13_14,
    MIGRATION_14_15,
    MIGRATION_15_16,
    MIGRATION_16_17,
)
