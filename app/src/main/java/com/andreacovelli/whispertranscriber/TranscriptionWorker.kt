package com.andreacovelli.whispertranscriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.andreacovelli.whispertranscriber.background.BackgroundShareNotifier
import com.andreacovelli.whispertranscriber.services.TranscriptionQueueService
import com.andreacovelli.whispertranscriber.transcription.JobRunOutcome
import com.andreacovelli.whispertranscriber.transcription.TranscriptionExecutionRunner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val queueService: TranscriptionQueueService,
    private val runner: TranscriptionExecutionRunner,
    private val backgroundShareNotifier: BackgroundShareNotifier,
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "transcription_channel"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo("Starting transcription...")

    private fun createForegroundInfo(progressText: String): ForegroundInfo {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transcription Service",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Transcribing Audio")
            .setContentText(progressText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun updateNotification(jobId: Int, progressText: String) {
        try {
            setForeground(createForegroundInfo(progressText))
            backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        } catch (e: IllegalStateException) {
            if (!isForegroundStartDenied(e)) throw e

            android.util.Log.w(
                "TranscriptionWorker",
                "Foreground service start denied after execution began.",
                e,
            )
            throw e
        }
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getInt(TranscriptionQueueService.QUEUED_JOB_ID_INPUT, -1)
        if (jobId == -1) return Result.failure()
        val speakerLabelPrefix = inputData.getString(TranscriptionQueueService.SPEAKER_LABEL_PREFIX_INPUT).orEmpty()
        try {
            setForeground(createForegroundInfo("Starting transcription..."))
        } catch (e: IllegalStateException) {
            if (!isForegroundStartDenied(e)) throw e
            return if (queueService.onForegroundPromotionDenied(jobId)) {
                Result.success()
            } else {
                Result.retry()
            }
        }

        return when (
            runner.runJob(
                jobId = jobId,
                executionOwnerId = id.toString(),
                speakerLabelPrefix = speakerLabelPrefix,
                onProgressNotification = { notificationJobId, _, progressText ->
                    updateNotification(notificationJobId, progressText)
                },
            )
        ) {
            JobRunOutcome.Success -> {
                queueService.onExecutionFinished(jobId)
                Result.success()
            }
            JobRunOutcome.Skipped -> {
                queueService.onExecutionFinished(jobId)
                Result.success()
            }
            JobRunOutcome.Superseded -> Result.success()
            JobRunOutcome.Retry -> Result.retry()
            JobRunOutcome.Failure -> {
                queueService.onExecutionFinished(jobId)
                Result.failure()
            }
        }
    }

    private fun isForegroundStartDenied(error: IllegalStateException): Boolean {
        if (error.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException") return true

        return generateSequence<Throwable>(error) { it.cause }
            .any { throwable ->
                throwable.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException" ||
                    throwable.message.orEmpty().contains("mAllowStartForeground false", ignoreCase = true) ||
                    throwable.message.orEmpty().contains("startForegroundService() not allowed", ignoreCase = true)
            }
    }
}
