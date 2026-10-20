package com.andreacovelli.whispertranscriber

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "transcription_channel"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(UserMessage(R.string.transcription_status_starting))

    private fun createForegroundInfo(progressText: UserMessage): ForegroundInfo {
        val jobId = inputData.getInt(TranscriptionQueueService.QUEUED_JOB_ID_INPUT, -1)
        val displayName = inputData.getString(TranscriptionQueueService.QUEUED_JOB_DISPLAY_NAME_INPUT)
            ?: applicationContext.getString(R.string.background_share_generic_audio_label)
        val notification = backgroundShareNotifier.buildProcessingNotification(jobId, displayName, progressText)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun promoteToForeground(progressText: UserMessage): Boolean = try {
        setForeground(createForegroundInfo(progressText))
        true
    } catch (error: IllegalStateException) {
        if (!isForegroundStartDenied(error)) throw error
        false
    } catch (_: SecurityException) {
        false
    }

    private suspend fun updateNotification(jobId: Int, progressText: UserMessage) {
        if (!promoteToForeground(progressText)) {
            throw com.andreacovelli.whispertranscriber.transcription.ProviderResponseException(
                R.string.transcription_foreground_denied_reopen,
            )
        }
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getInt(TranscriptionQueueService.QUEUED_JOB_ID_INPUT, -1)
        if (jobId == -1) return Result.failure()
        val speakerLabelPrefix = inputData.getString(TranscriptionQueueService.SPEAKER_LABEL_PREFIX_INPUT).orEmpty()
        if (!promoteToForeground(UserMessage(R.string.transcription_status_starting))) {
            return if (queueService.onForegroundPromotionDenied(jobId)) {
                Result.success()
            } else {
                Result.failure()
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
