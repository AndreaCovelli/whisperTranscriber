package com.andreacovelli.whispertranscriber

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CancelTranscriptionNotificationReceiver : BroadcastReceiver() {
    @Inject
    lateinit var queueService: TranscriptionQueueService

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_JOB) return

        val jobId = intent.getIntExtra(EXTRA_JOB_ID, -1)
        if (jobId == -1) return

        val pendingResult = goAsync()
        receiverScope.launch {
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

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
