package com.andreacovelli.whispertranscriber.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [TranscriptionHistory::class, TranscriptionHistoryFts::class, TranscriptionJob::class],
    version = 17,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptionHistoryDao(): TranscriptionHistoryDao
    abstract fun transcriptionJobDao(): TranscriptionJobDao
    abstract fun transcriptionJobSummarizationDao(): TranscriptionJobSummarizationDao

    companion object {
        private const val DATABASE_NAME = "transcription_database"

        val MIGRATIONS: Array<Migration> = DATABASE_MIGRATIONS

        /** Builds the app database. Hilt owns the single instance; see `AppModule.provideDatabase`. */
        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            DATABASE_NAME,
        ).apply {
            MIGRATIONS.forEach { migration -> addMigrations(migration) }
        }.build()
    }
}
