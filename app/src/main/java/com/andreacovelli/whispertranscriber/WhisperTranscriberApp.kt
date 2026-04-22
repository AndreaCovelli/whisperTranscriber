package com.andreacovelli.whispertranscriber

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class WhisperTranscriberApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var repository: TranscriptionRepository

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    override fun onCreate() {
        super.onCreate()
        runBlocking(ioDispatcher) {
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
