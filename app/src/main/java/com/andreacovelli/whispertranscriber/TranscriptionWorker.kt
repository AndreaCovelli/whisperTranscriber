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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

internal class TransientWorkException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

@Suppress("unused")
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val queueService: TranscriptionQueueService,
    private val runner: TranscriptionExecutionRunner,
    private val backgroundShareNotifier: BackgroundShareNotifier,
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var foregroundUnavailable = false

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "transcription_channel"
        const val SPEAKER_LABEL_PREFIX_INPUT = "speakerLabelPrefix"
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

    private suspend fun updateNotification(jobId: Int, displayName: String, progressText: String) {
        if (foregroundUnavailable) {
            backgroundShareNotifier.showBackgroundProcessing(jobId, displayName, progressText)
            return
        }

        try {
            setForeground(createForegroundInfo(progressText))
            backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        } catch (e: IllegalStateException) {
            if (!isForegroundStartDenied(e)) throw e

            foregroundUnavailable = true
            android.util.Log.w(
                "TranscriptionWorker",
                "Foreground service start denied. Continuing without foreground service notification.",
                e,
            )
            backgroundShareNotifier.showBackgroundProcessing(jobId, displayName, progressText)
        }
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getInt(TranscriptionQueueService.QUEUED_JOB_ID_INPUT, -1)
        if (jobId == -1) return Result.failure()

        return when (
            runner.runJob(
                jobId = jobId,
                onProgressNotification = ::updateNotification,
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
            JobRunOutcome.Retry -> {
                queueService.scheduleNextQueuedJobIfIdle()
                Result.retry()
            }
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
