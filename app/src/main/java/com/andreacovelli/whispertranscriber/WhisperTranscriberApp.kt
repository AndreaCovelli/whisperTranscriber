package com.andreacovelli.whispertranscriber

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.andreacovelli.whispertranscriber.di.ApplicationScope
import com.andreacovelli.whispertranscriber.queue.WORK_MANAGER_JOB_ID_RANGE_MAX
import com.andreacovelli.whispertranscriber.queue.WORK_MANAGER_JOB_ID_RANGE_MIN
import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class WhisperTranscriberApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var repository: TranscriptionRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            repository.resetStaleJobs()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setJobSchedulerJobIdRange(
                WORK_MANAGER_JOB_ID_RANGE_MIN,
                WORK_MANAGER_JOB_ID_RANGE_MAX,
            )
            .build()
}
