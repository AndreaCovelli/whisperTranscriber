package com.andreacovelli.whispertranscriber.background

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.services.SummarizationService
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take

/** Runs a user-requested summary outside any screen; the summary claim in Room prevents duplicates. */
@HiltWorker
class SummaryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val summarizationService: SummarizationService,
    private val backgroundShareNotifier: BackgroundShareNotifier,
) : CoroutineWorker(appContext, workerParams) {

    // Only used when expedited work runs as a foreground service before Android 12.
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = backgroundShareNotifier.buildProcessingNotification(
            jobId = inputData.getInt(JOB_ID_INPUT, -1),
            displayName = inputData.getString(DISPLAY_NAME_INPUT).orEmpty(),
            message = applicationContext.getString(R.string.transcription_notification_summarizing),
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getInt(JOB_ID_INPUT, -1)
        if (jobId == -1) return Result.failure()
        val provider = runCatching { TranscriptionProvider.valueOf(inputData.getString(PROVIDER_INPUT).orEmpty()) }
            .getOrDefault(TranscriptionProvider.GEMINI_FLASH)
        val result = summarizationService.summarize(
            jobId = jobId,
            settings = SummarizationSettings(
                provider = provider,
                customModelName = inputData.getString(CUSTOM_MODEL_INPUT).orEmpty(),
            ),
        )
        val output = workDataOf(STATUS_MESSAGE_OUTPUT to result.statusMessage)
        // Failures are already stored on the job, so the work itself always completes.
        return Result.success(output)
    }

    internal companion object {
        const val JOB_ID_INPUT = "summary_job_id"
        const val DISPLAY_NAME_INPUT = "summary_display_name"
        const val PROVIDER_INPUT = "summary_provider"
        const val CUSTOM_MODEL_INPUT = "summary_custom_model"
        const val STATUS_MESSAGE_OUTPUT = "summary_status_message"
        private const val NOTIFICATION_ID = 1002
    }
}

/** Enqueues and cancels user-requested summaries, one unique work entry per job. */
@Singleton
class SummaryWorkScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val summarizationService: SummarizationService,
) {
    /** Returns the id of the new work, or of the pending work already summarizing this job. */
    suspend fun enqueue(jobId: Int, displayName: String, settings: SummarizationSettings): UUID {
        workManager.getWorkInfosForUniqueWorkFlow(workName(jobId)).first()
            .firstOrNull { info -> !info.state.isFinished }
            ?.let { pending -> return pending.id }
        val request = OneTimeWorkRequestBuilder<SummaryWorker>()
            .setInputData(
                workDataOf(
                    SummaryWorker.JOB_ID_INPUT to jobId,
                    SummaryWorker.DISPLAY_NAME_INPUT to displayName,
                    SummaryWorker.PROVIDER_INPUT to settings.provider.name,
                    SummaryWorker.CUSTOM_MODEL_INPUT to settings.customModelName,
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // KEEP: a request racing a pending one would lose the summary claim anyway.
        workManager.enqueueUniqueWork(workName(jobId), ExistingWorkPolicy.KEEP, request).await()
        return request.id
    }

    /** Emits the status message once the given summary work finishes, then completes. */
    fun statusMessages(workId: UUID): Flow<String> = workManager.getWorkInfoByIdFlow(workId)
        .filter { info -> info?.state?.isFinished == true }
        .take(1)
        .map { info ->
            info?.takeIf { it.state == WorkInfo.State.SUCCEEDED }
                ?.outputData
                ?.getString(SummaryWorker.STATUS_MESSAGE_OUTPUT)
                .orEmpty()
        }

    /** Cancels the stored attempt first so a late provider response cannot be saved. */
    suspend fun cancel(jobId: Int): String {
        val statusMessage = summarizationService.cancelSummary(jobId)
        workManager.cancelUniqueWork(workName(jobId)).await()
        return statusMessage
    }

    private fun workName(jobId: Int): String = "SummaryWork_$jobId"
}
