@file:Suppress("unused") // Hilt consumes these modules and bindings from generated code, not direct Kotlin call sites.

package com.andreacovelli.whispertranscriber.di

import android.content.Context
import androidx.work.WorkManager
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.HistoryRepository
import com.andreacovelli.whispertranscriber.data.RoomHistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionHistoryDao
import com.andreacovelli.whispertranscriber.data.TranscriptionJobDao
import com.andreacovelli.whispertranscriber.data.TranscriptionJobSummarizationDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.getDatabase(context)

    @Provides
    fun provideTranscriptionHistoryDao(database: AppDatabase): TranscriptionHistoryDao =
        database.transcriptionHistoryDao()

    @Provides
    fun provideTranscriptionJobDao(database: AppDatabase): TranscriptionJobDao = database.transcriptionJobDao()

    @Provides
    fun provideTranscriptionJobSummarizationDao(database: AppDatabase): TranscriptionJobSummarizationDao =
        database.transcriptionJobSummarizationDao()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
            redactHeader("Ocp-Apim-Subscription-Key")
            redactHeader("x-goog-api-key")
        }

        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Long-running uploads and transcriptions can keep the socket open for large files.
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindHistoryRepository(repository: RoomHistoryRepository): HistoryRepository
}
