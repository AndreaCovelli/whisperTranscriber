package com.andreacovelli.whispertranscriber

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BackgroundShareJobService : JobService() {
    @Inject
    lateinit var runner: TranscriptionExecutionRunner

    @Inject
    lateinit var queueService: TranscriptionQueueService

    @Inject
    lateinit var repository: TranscriptionRepository

    @Inject
    lateinit var backgroundShareNotifier: BackgroundShareNotifier

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
    private val runningParams = ConcurrentHashMap<Int, JobParameters>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false

        val jobId = params.extras.getInt(TranscriptionQueueService.QUEUED_JOB_ID_INPUT, -1)
        if (jobId == -1) return false

        val displayName = params.extras.getString(
            TranscriptionQueueService.QUEUED_JOB_DISPLAY_NAME_INPUT,
        ).orEmpty().ifBlank {
            getString(R.string.background_share_generic_audio_label)
        }
        runningParams[jobId] = params
        setNotification(
            params,
            notificationId(jobId),
            backgroundShareNotifier.buildProcessingNotification(
                jobId = jobId,
                displayName = displayName,
                message = getString(R.string.background_share_waiting_to_start_notification_body),
            ),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )

        runningJobs[jobId] = serviceScope.launch {
            try {
                when (
                    runner.runJob(
                        jobId = jobId,
                        onProgressNotification = ::updateNotification,
                    )
                ) {
                    JobRunOutcome.Success -> {
                        queueService.onExecutionFinished(jobId)
                        jobFinished(params, false)
                    }
                    JobRunOutcome.Skipped -> jobFinished(params, false)
                    JobRunOutcome.Retry -> jobFinished(params, true)
                    JobRunOutcome.Failure -> {
                        queueService.onExecutionFinished(jobId)
                        jobFinished(params, false)
                    }
                }
            } finally {
                runningJobs.remove(jobId)
                runningParams.remove(jobId)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val jobId = params.extras.getInt(TranscriptionQueueService.QUEUED_JOB_ID_INPUT, -1)
        if (jobId != -1) {
            runningJobs.remove(jobId)?.cancel()
            runningParams.remove(jobId)
            serviceScope.launch {
                repository.resetRunningToQueued(jobId)
            }
        }
        return true
    }

    override fun onDestroy() {
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        runningParams.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(jobId: Int, displayName: String, progressText: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        val params = runningParams[jobId] ?: return
        setNotification(
            params,
            notificationId(jobId),
            backgroundShareNotifier.buildProcessingNotification(jobId, displayName, progressText),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
    }

    private fun notificationId(jobId: Int): Int = 200_000 + jobId
}
