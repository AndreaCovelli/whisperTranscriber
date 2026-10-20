package com.andreacovelli.whispertranscriber

import android.annotation.SuppressLint
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import android.util.Log
import com.andreacovelli.whispertranscriber.background.BackgroundShareNotifier
import com.andreacovelli.whispertranscriber.background.BackgroundShareRunCoordinator
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.queue.backgroundShareSchedulerJobId
import com.andreacovelli.whispertranscriber.services.TranscriptionQueueService
import com.andreacovelli.whispertranscriber.transcription.JobRunOutcome
import com.andreacovelli.whispertranscriber.transcription.TranscriptionExecutionRunner
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

@SuppressLint("SpecifyJobSchedulerIdRange") // WorkManager range is configured in WhisperTranscriberApp.
@AndroidEntryPoint
class BackgroundShareJobService : JobService() {
    @Inject
    lateinit var runner: TranscriptionExecutionRunner

    @Inject
    lateinit var queueService: TranscriptionQueueService

    @Inject
    lateinit var backgroundShareNotifier: BackgroundShareNotifier

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    // Uncaught failures must not crash the process, which would also kill unrelated queued work.
    // Lazy because Hilt injects the dispatcher in onCreate, after property initialization.
    private val serviceScope by lazy {
        CoroutineScope(
            SupervisorJob() + ioDispatcher + CoroutineExceptionHandler { _, error ->
                Log.w(TAG, "Background share job failed with ${error::class.java.name}")
            },
        )
    }
    private val runCoordinator = BackgroundShareRunCoordinator()
    private val runningJobs = ConcurrentHashMap<Int, Job>()
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
        val speakerLabelPrefix = params.extras.getString(TranscriptionQueueService.SPEAKER_LABEL_PREFIX_INPUT)
            .orEmpty()
        runningParams[jobId] = params
        try {
            setNotification(
                params,
                notificationId(jobId),
                backgroundShareNotifier.buildProcessingNotification(
                    jobId = jobId,
                    displayName = displayName,
                    message = UserMessage(R.string.background_share_waiting_to_start_notification_body),
                ),
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        } catch (_: RuntimeException) {
            serviceScope.launch {
                try {
                    queueService.failBackgroundStart(jobId)
                } finally {
                    runningParams.remove(jobId, params)
                    jobFinished(params, false)
                }
            }
            return true
        }

        val executionOwnerId = runCoordinator.nextExecutionOwnerId(params.jobId)

        @Suppress("TooGenericExceptionCaught") // Any escaped failure must still finish the JobScheduler job.
        val runningJob = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                runCoordinator.runSerialized {
                    when (
                        runner.runJob(
                            jobId = jobId,
                            executionOwnerId = executionOwnerId,
                            speakerLabelPrefix = speakerLabelPrefix,
                            onProgressNotification = ::updateNotification,
                        )
                    ) {
                        JobRunOutcome.Success -> {
                            queueService.onExecutionFinished(jobId)
                            jobFinished(params, false)
                        }
                        JobRunOutcome.Skipped -> {
                            queueService.onExecutionFinished(jobId)
                            jobFinished(params, false)
                        }
                        JobRunOutcome.Superseded -> jobFinished(params, false)
                        JobRunOutcome.Retry -> jobFinished(params, true)
                        JobRunOutcome.Failure -> {
                            queueService.onExecutionFinished(jobId)
                            jobFinished(params, false)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The runner releases its own claim; this only keeps the queue and JobScheduler moving.
                Log.w(TAG, "Background transcription failed with ${e::class.java.name}")
                try {
                    queueService.onExecutionFinished(jobId)
                } finally {
                    jobFinished(params, false)
                }
            } finally {
                runningJobs.remove(jobId, coroutineContext.job)
                runningParams.remove(jobId, params)
            }
        }
        runningJobs[jobId] = runningJob
        runningJob.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val jobId = params.extras.getInt(TranscriptionQueueService.QUEUED_JOB_ID_INPUT, -1)
        if (jobId != -1) {
            runningJobs.remove(jobId)?.cancel()
            runningParams.remove(jobId)
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

    private fun updateNotification(jobId: Int, displayName: String, progressText: UserMessage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        val params = runningParams[jobId] ?: return
        setNotification(
            params,
            notificationId(jobId),
            backgroundShareNotifier.buildProcessingNotification(jobId, displayName, progressText),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
    }

    private fun notificationId(jobId: Int): Int = backgroundShareSchedulerJobId(jobId)

    private companion object {
        const val TAG = "BackgroundShareJobService"
    }
}
