package com.andreacovelli.whispertranscriber

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WhisperTranscriberApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setJobSchedulerJobIdRange(
                WORK_MANAGER_JOB_ID_RANGE_MIN,
                WORK_MANAGER_JOB_ID_RANGE_MAX,
            )
            .build()
}
