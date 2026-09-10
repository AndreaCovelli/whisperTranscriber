package com.andreacovelli.whispertranscriber.queue

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.andreacovelli.whispertranscriber.BackgroundShareJobService
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.TranscriptionWorker
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.services.TranscriptionQueueService.Companion.QUEUED_JOB_DISPLAY_NAME_INPUT
import com.andreacovelli.whispertranscriber.services.TranscriptionQueueService.Companion.QUEUED_JOB_ID_INPUT
import com.andreacovelli.whispertranscriber.services.TranscriptionQueueService.Companion.SPEAKER_LABEL_PREFIX_INPUT
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

interface QueueScheduler {
    val supportsUserInitiatedJobs: Boolean
    suspend fun hasActiveEntry(job: TranscriptionJob): Boolean
    suspend fun enqueueWorker(job: TranscriptionJob): Boolean
    fun enqueueUserInitiatedJob(job: TranscriptionJob): Boolean
    suspend fun cancel(jobId: Int)
}

@Singleton
class AndroidQueueScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workManager: WorkManager,
) : QueueScheduler {
    override val supportsUserInitiatedJobs: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    override suspend fun hasActiveEntry(job: TranscriptionJob): Boolean = when (job.executionBackendOrDefault()) {
        JobExecutionBackend.WORK_MANAGER -> workManager.getWorkInfosForUniqueWorkFlow(jobWorkName(job.id))
            .first()
            .any { workInfo ->
                workInfo.state == WorkInfo.State.ENQUEUED ||
                    workInfo.state == WorkInfo.State.RUNNING ||
                    workInfo.state == WorkInfo.State.BLOCKED
            }
        JobExecutionBackend.USER_INITIATED_JOB -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                false
            } else {
                context.getSystemService(JobScheduler::class.java)
                    ?.allPendingJobs
                    ?.any { pending -> pending.id == backgroundShareSchedulerJobId(job.id) }
                    ?: false
            }
        }
    }

    override suspend fun enqueueWorker(job: TranscriptionJob): Boolean {
        val workRequestBuilder = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(
                workDataOf(
                    QUEUED_JOB_ID_INPUT to job.id,
                    QUEUED_JOB_DISPLAY_NAME_INPUT to job.displayName,
                    SPEAKER_LABEL_PREFIX_INPUT to context.getString(R.string.speaker_label_prefix),
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(jobTag(job.id))

        // Promote inside doWork so a denied foreground start can update the persisted queue.
        // Expedited work can fail promotion before doWork gets that opportunity on older Android.
        val workRequest = workRequestBuilder.build()

        workManager.enqueueUniqueWork(jobWorkName(job.id), androidx.work.ExistingWorkPolicy.KEEP, workRequest).await()
        return true
    }

    override fun enqueueUserInitiatedJob(job: TranscriptionJob): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false

        return try {
            val jobScheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            if (!jobScheduler.canRunUserInitiatedJobs()) return false
            val schedulerJobId = backgroundShareSchedulerJobId(job.id)

            val extras = PersistableBundle().apply {
                putInt(QUEUED_JOB_ID_INPUT, job.id)
                putString(QUEUED_JOB_DISPLAY_NAME_INPUT, job.displayName)
                putString(SPEAKER_LABEL_PREFIX_INPUT, context.getString(R.string.speaker_label_prefix))
            }
            val jobInfo = JobInfo.Builder(schedulerJobId, ComponentName(context, BackgroundShareJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(extras)
                .setUserInitiated(true)
                .build()

            jobScheduler.schedule(jobInfo) == JobScheduler.RESULT_SUCCESS
        } catch (_: RuntimeException) {
            // JobScheduler can reject construction, permission checks, or scheduling.
            false
        }
    }

    override suspend fun cancel(jobId: Int) {
        workManager.cancelUniqueWork(jobWorkName(jobId)).await()
        workManager.cancelAllWorkByTag(jobTag(jobId)).await()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(JobScheduler::class.java)?.cancel(backgroundShareSchedulerJobId(jobId))
        }
    }

    private fun jobTag(jobId: Int): String = "TranscriptionJob_$jobId"

    private fun jobWorkName(jobId: Int): String = "TranscriptionJobWork_$jobId"
}
