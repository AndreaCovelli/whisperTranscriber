package com.andreacovelli.whispertranscriber

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andreacovelli.whispertranscriber.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CancelTranscriptionNotificationReceiver : BroadcastReceiver() {
    @Inject
    lateinit var queueService: TranscriptionQueueService

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_JOB) return

        val jobId = intent.getIntExtra(EXTRA_JOB_ID, -1)
        if (jobId == -1) return

        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                queueService.cancelJob(jobId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_JOB = "com.andreacovelli.whispertranscriber.action.CANCEL_JOB"
        const val EXTRA_JOB_ID = "jobId"
    }
}
