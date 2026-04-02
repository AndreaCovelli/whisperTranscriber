package com.andreacovelli.whispertranscriber.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcription_history")
data class TranscriptionHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String,
    val language: String,
    val model: String = "",
    val summary: String = "",
    val displayName: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

@Fts4
@Entity(tableName = "transcription_history_fts")
data class TranscriptionHistoryFts(
    @PrimaryKey
    @androidx.room.ColumnInfo(name = "rowid")
    val rowId: Int,
    val text: String,
    val summary: String,
    val displayName: String,
)

@Entity(tableName = "active_jobs")
data class TranscriptionJob(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val uri: String,
    val displayName: String,
    val sourceMimeType: String? = null,
    val stagedFilePath: String? = null,
    val providerName: String = "ELEVEN_LABS",
    val requestedModel: String? = null,
    val status: String = "Queued",
    val progress: Float = 0f,
    val transcript: String = "",
    val language: String = "",
    val model: String = "",
    val summary: String = "",
    val error: String? = null,
    val summaryError: String? = null,
    val isRunning: Boolean = false,
    val isSummarizing: Boolean = false,
    val historyId: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

@Dao
interface TranscriptionHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(historyItem: TranscriptionHistory): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(historyItem: TranscriptionHistoryFts)

    @Query("DELETE FROM transcription_history WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM transcription_history_fts WHERE rowid = :id")
    suspend fun deleteFtsById(id: Int)

    @Query("DELETE FROM transcription_history")
    suspend fun deleteAllHistory()

    @Query("DELETE FROM transcription_history_fts")
    suspend fun deleteAllFts()

    @Query("SELECT * FROM transcription_history ORDER BY timestamp DESC")
    fun getAllHistoryPaged(): PagingSource<Int, TranscriptionHistory>

    @Query(
        """
        SELECT h.*
        FROM transcription_history AS h
        INNER JOIN transcription_history_fts AS fts ON h.id = fts.rowid
        WHERE transcription_history_fts MATCH :query
        ORDER BY h.timestamp DESC
        """,
    )
    fun searchHistoryPaged(query: String): PagingSource<Int, TranscriptionHistory>

    @Query("UPDATE transcription_history SET summary = :summary WHERE id = :id")
    suspend fun updateSummaryValue(id: Int, summary: String)

    @Query("UPDATE transcription_history_fts SET summary = :summary WHERE rowid = :id")
    suspend fun updateFtsSummary(id: Int, summary: String)

    @Query("UPDATE transcription_history SET text = :text WHERE id = :id")
    suspend fun updateTextValue(id: Int, text: String)

    @Query("UPDATE transcription_history_fts SET text = :text WHERE rowid = :id")
    suspend fun updateFtsText(id: Int, text: String)

    @Query("SELECT * FROM transcription_history WHERE id = :id")
    suspend fun getById(id: Int): TranscriptionHistory?

    @Transaction
    suspend fun insert(historyItem: TranscriptionHistory): Long {
        val id = insertHistory(historyItem).toInt()
        insertFts(
            TranscriptionHistoryFts(
                rowId = id,
                text = historyItem.text,
                summary = historyItem.summary,
                displayName = historyItem.displayName,
            ),
        )
        return id.toLong()
    }

    @Transaction
    suspend fun delete(historyItem: TranscriptionHistory) {
        deleteById(historyItem.id)
        deleteFtsById(historyItem.id)
    }

    @Transaction
    suspend fun deleteAll() {
        deleteAllHistory()
        deleteAllFts()
    }

    @Transaction
    suspend fun updateSummary(id: Int, summary: String) {
        updateSummaryValue(id, summary)
        updateFtsSummary(id, summary)
    }

    @Transaction
    suspend fun updateText(id: Int, text: String) {
        updateTextValue(id, text)
        updateFtsText(id, text)
    }
}

@Dao
interface TranscriptionJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: TranscriptionJob): Long

    @Update
    suspend fun update(job: TranscriptionJob)

    @Query("UPDATE active_jobs SET isRunning = :isRunning, status = :status WHERE id = :id")
    suspend fun updateRunning(id: Int, isRunning: Boolean, status: String)

    @Query("UPDATE active_jobs SET status = :status, progress = :progress WHERE id = :id")
    suspend fun updateStatusAndProgress(id: Int, status: String, progress: Float)

    @Query(
        "UPDATE active_jobs SET transcript = :transcript, language = :language, model = :model, historyId = :historyId, progress = :progress, isRunning = :isRunning, status = :status, error = :error WHERE id = :id",
    )
    suspend fun updateResult(
        id: Int,
        transcript: String,
        language: String,
        model: String,
        historyId: Int?,
        progress: Float,
        isRunning: Boolean,
        status: String,
        error: String?,
    )

    @Query("UPDATE active_jobs SET error = :error, isRunning = :isRunning, status = :status WHERE id = :id")
    suspend fun updateError(id: Int, error: String, isRunning: Boolean, status: String)

    @Query("UPDATE active_jobs SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String)

    @Query(
        "UPDATE active_jobs SET transcript = '', language = '', model = '', summary = '', status = 'Cleared' WHERE id = :id",
    )
    suspend fun clearTranscriptionResult(id: Int)

    @Query("UPDATE active_jobs SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: Int, summary: String)

    @Query("UPDATE active_jobs SET isSummarizing = :isSummarizing WHERE id = :id")
    suspend fun setSummarizing(id: Int, isSummarizing: Boolean)

    @Query("UPDATE active_jobs SET summaryError = :error WHERE id = :id")
    suspend fun updateSummaryError(id: Int, error: String?)

    @Query("UPDATE active_jobs SET isSummarizing = 0, summary = :summary, summaryError = NULL WHERE id = :id")
    suspend fun succeedSummarization(id: Int, summary: String)

    @Query("UPDATE active_jobs SET isSummarizing = 0, summaryError = :error WHERE id = :id")
    suspend fun failSummarization(id: Int, error: String?)

    @Query(
        """
        UPDATE active_jobs
        SET isSummarizing = 1, summaryError = NULL
        WHERE id = :id
          AND isRunning = 0
          AND isSummarizing = 0
          AND transcript != ''
          AND summary = ''
          AND error IS NULL
          AND summaryError IS NULL
        """,
    )
    suspend fun claimAutoSummary(id: Int): Int

    @Query(
        """
        UPDATE active_jobs
        SET isSummarizing = 1, summaryError = NULL
        WHERE id = :id
          AND isRunning = 0
          AND isSummarizing = 0
          AND transcript != ''
          AND error IS NULL
        """,
    )
    suspend fun claimManualSummary(id: Int): Int

    @Query("UPDATE active_jobs SET isSummarizing = 0 WHERE isSummarizing = 1")
    suspend fun resetStaleSummaries(): Int

    @Query("SELECT * FROM active_jobs ORDER BY timestamp ASC")
    fun getAllJobs(): Flow<List<TranscriptionJob>>

    @Query("SELECT * FROM active_jobs WHERE id = :id")
    suspend fun getJobById(id: Int): TranscriptionJob?

    @Query("SELECT * FROM active_jobs WHERE isRunning = 0 AND status != 'Queued'")
    suspend fun getCompletedJobs(): List<TranscriptionJob>

    @Query("DELETE FROM active_jobs WHERE id = :jobId")
    suspend fun deleteById(jobId: Int)

    @Query("DELETE FROM active_jobs WHERE isRunning = 0 AND status != 'Queued'")
    suspend fun clearCompleted()
}

@Database(
    entities = [TranscriptionHistory::class, TranscriptionHistoryFts::class, TranscriptionJob::class],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptionHistoryDao(): TranscriptionHistoryDao
    abstract fun transcriptionJobDao(): TranscriptionJobDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

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
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN providerName TEXT NOT NULL DEFAULT 'ELEVEN_LABS'")
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN requestedModel TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS `transcription_history_fts`
                    USING fts4(`text`, `summary`, `displayName`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO transcription_history_fts(rowid, text, summary, displayName)
                    SELECT id, text, summary, displayName
                    FROM transcription_history
                    """.trimIndent(),
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "transcription_database",
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
                .build()
            INSTANCE = instance
            instance
        }
    }
}
