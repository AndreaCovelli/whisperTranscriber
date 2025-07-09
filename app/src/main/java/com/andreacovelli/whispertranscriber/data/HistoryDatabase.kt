package com.andreacovelli.whispertranscriber.data

import android.content.Context
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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcription_history")
data class TranscriptionHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String,
    val language: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface TranscriptionHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(historyItem: TranscriptionHistory)

    @Delete
    suspend fun delete(historyItem: TranscriptionHistory)

    @Query("SELECT * FROM transcription_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<TranscriptionHistory>>

    @Query("SELECT * FROM transcription_history WHERE id = :id")
    fun getHistoryItemById(id: Int): Flow<TranscriptionHistory?>
}

@Database(entities = [TranscriptionHistory::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptionHistoryDao(): TranscriptionHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "transcription_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}