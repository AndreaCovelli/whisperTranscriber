package com.andreacovelli.whispertranscriber.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "active_jobs")
data class TranscriptionJob(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val uri: String,
    val displayName: String,
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
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface TranscriptionHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(historyItem: TranscriptionHistory): Long

    @Delete
    suspend fun delete(historyItem: TranscriptionHistory)

    @Query("DELETE FROM transcription_history")
    suspend fun deleteAll()

    @Query("SELECT * FROM transcription_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<TranscriptionHistory>>

    @Query("SELECT * FROM transcription_history ORDER BY timestamp DESC")
    fun getAllHistoryPaged(): PagingSource<Int, TranscriptionHistory>

    @Query("SELECT * FROM transcription_history WHERE displayName LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchHistoryPaged(query: String): PagingSource<Int, TranscriptionHistory>

    @Query("SELECT * FROM transcription_history WHERE id = :id")
    fun getHistoryItemById(id: Int): Flow<TranscriptionHistory?>

    @Query("UPDATE transcription_history SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: Int, summary: String)

    @Query("UPDATE transcription_history SET text = :text WHERE id = :id")
    suspend fun updateText(id: Int, text: String)
}

@Dao
interface TranscriptionJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: TranscriptionJob): Long

    @Update
    suspend fun update(job: TranscriptionJob)

    @Delete
    suspend fun delete(job: TranscriptionJob)

    @Query("UPDATE active_jobs SET isRunning = :isRunning, status = :status WHERE id = :id")
    suspend fun updateRunning(id: Int, isRunning: Boolean, status: String)

    @Query("UPDATE active_jobs SET status = :status, progress = :progress WHERE id = :id")
    suspend fun updateStatusAndProgress(id: Int, status: String, progress: Float)

    @Query("UPDATE active_jobs SET transcript = :transcript, language = :language, model = :model, historyId = :historyId, progress = :progress, isRunning = :isRunning, error = :error WHERE id = :id")
    suspend fun updateResult(id: Int, transcript: String, language: String, model: String, historyId: Int?, progress: Float, isRunning: Boolean, error: String?)

    @Query("UPDATE active_jobs SET error = :error, isRunning = :isRunning, status = :status WHERE id = :id")
    suspend fun updateError(id: Int, error: String, isRunning: Boolean, status: String)

    @Query("UPDATE active_jobs SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String)

    @Query("UPDATE active_jobs SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: Int, summary: String)

    @Query("UPDATE active_jobs SET isSummarizing = :isSummarizing WHERE id = :id")
    suspend fun setSummarizing(id: Int, isSummarizing: Boolean)

    @Query("UPDATE active_jobs SET summaryError = :error WHERE id = :id")
    suspend fun updateSummaryError(id: Int, error: String?)

    @Query("SELECT * FROM active_jobs ORDER BY timestamp ASC")
    fun getAllJobs(): Flow<List<TranscriptionJob>>

    @Query("SELECT * FROM active_jobs WHERE id = :id")
    suspend fun getJobById(id: Int): TranscriptionJob?

    @Query("DELETE FROM active_jobs WHERE id = :jobId")
    suspend fun deleteById(jobId: Int)

    @Query("DELETE FROM active_jobs WHERE isRunning = 0 AND (transcript != '' OR error IS NOT NULL)")
    suspend fun clearCompleted()
}

@Database(entities = [TranscriptionHistory::class, TranscriptionJob::class], version = 6, exportSchema = false)
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
                db.execSQL("""
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
                """.trimIndent())
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE active_jobs ADD COLUMN summaryError TEXT")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "transcription_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}