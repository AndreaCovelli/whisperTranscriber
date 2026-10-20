package com.andreacovelli.whispertranscriber

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.andreacovelli.whispertranscriber.background.BackgroundShareNotifier
import com.andreacovelli.whispertranscriber.background.StoredResultPublisher
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.RoomHistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.queue.JobOperationRegistry
import com.andreacovelli.whispertranscriber.queue.QueueExecutionCoordinator
import com.andreacovelli.whispertranscriber.queue.QueueScheduler
import com.andreacovelli.whispertranscriber.queue.QueuedAudioStore
import com.andreacovelli.whispertranscriber.security.DefaultGenAiClientFactory
import com.andreacovelli.whispertranscriber.services.SummarizationService
import com.andreacovelli.whispertranscriber.services.TranscriptionQueueService
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.summary.TranscriptionSummarizationRepository
import com.andreacovelli.whispertranscriber.transcription.ProviderTranscriberFactory
import com.andreacovelli.whispertranscriber.transcription.TranscriptionAudioPreparer
import com.andreacovelli.whispertranscriber.transcription.TranscriptionExecutionRunner
import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

/** Real Room, storage, and queue orchestration; only OS scheduling is recorded. */
internal class RepairTestFixture(
    context: Context,
    queryCallback: RoomDatabase.QueryCallback? = null,
    val settings: ApiKeyManager = ApiKeyManager(context),
) : AutoCloseable {
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).apply {
        if (queryCallback != null) setQueryCallback(queryCallback, Runnable::run)
    }.build()
    val history = RoomHistoryRepository(db, db.transcriptionHistoryDao(), db.transcriptionJobDao())
    val repository = TranscriptionRepository(db, db.transcriptionJobDao(), db.transcriptionHistoryDao(), history)
    val summaries = TranscriptionSummarizationRepository(db, db.transcriptionJobSummarizationDao(), history)
    val operations = JobOperationRegistry()
    val coordinator = QueueExecutionCoordinator(repository, operations)
    val notifier = BackgroundShareNotifier(context)
    val publisher = StoredResultPublisher(db, notifier)
    val audioStore = QueuedAudioStore(context, Dispatchers.IO)
    val scheduler = RecordingScheduler()
    val queue = TranscriptionQueueService(
        settings,
        repository,
        audioStore,
        scheduler,
        history,
        notifier,
        coordinator,
        operations,
    )
    private val httpClient = OkHttpClient()
    private val clients = DefaultGenAiClientFactory(httpClient)
    val summaryService = SummarizationService(
        settings,
        repository,
        summaries,
        clients,
        coordinator,
        operations,
        Dispatchers.IO,
    )
    val runner = TranscriptionExecutionRunner(
        context,
        repository,
        coordinator,
        summaryService,
        notifier,
        publisher,
        audioStore,
        TranscriptionAudioPreparer(context, Dispatchers.IO),
        ProviderTranscriberFactory(settings, clients, httpClient, Dispatchers.IO),
        Dispatchers.IO,
    )

    override fun close() = db.close()
}

internal class RecordingScheduler : QueueScheduler {
    override var supportsUserInitiatedJobs = false
    var allowUserInitiatedJob = false
    val active = mutableSetOf<Int>()
    val scheduled = mutableListOf<Int>()
    override suspend fun hasActiveEntry(job: TranscriptionJob): Boolean = job.id in active
    override suspend fun enqueueWorker(job: TranscriptionJob): Boolean {
        scheduled += job.id
        active += job.id
        return true
    }
    override fun enqueueUserInitiatedJob(job: TranscriptionJob): Boolean = allowUserInitiatedJob.also { accepted ->
        if (accepted) active += job.id
    }
    override suspend fun cancel(jobId: Int) {
        active -= jobId
    }
}
