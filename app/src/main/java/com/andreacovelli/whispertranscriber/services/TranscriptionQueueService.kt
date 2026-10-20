package com.andreacovelli.whispertranscriber.services

import android.content.Context
import android.net.Uri
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.background.BackgroundShareNotifier
import com.andreacovelli.whispertranscriber.background.PostTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.HistoryJobCleanup
import com.andreacovelli.whispertranscriber.data.HistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.queue.JobExecutionBackend
import com.andreacovelli.whispertranscriber.queue.JobOperationRegistry
import com.andreacovelli.whispertranscriber.queue.PersistedPostTranscriptionConfig
import com.andreacovelli.whispertranscriber.queue.QueueExecutionCoordinator
import com.andreacovelli.whispertranscriber.queue.QueuedAudioStore
import com.andreacovelli.whispertranscriber.queue.RetryResult
import com.andreacovelli.whispertranscriber.queue.SharedAudioIntake
import com.andreacovelli.whispertranscriber.queue.executionBackendOrDefault
import com.andreacovelli.whispertranscriber.queue.resolveShareExecutionBackend
import com.andreacovelli.whispertranscriber.queue.toPersistedConfig
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class QueuedShareResult(val jobsCreated: Int, val statusMessage: String, val firstCreatedJobId: Int? = null)

@Singleton
class TranscriptionQueueService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: ApiKeyManager,
    private val repository: TranscriptionRepository,
    private val queuedAudioStore: QueuedAudioStore,
    private val scheduler: com.andreacovelli.whispertranscriber.queue.QueueScheduler,
    private val historyRepository: HistoryRepository,
    private val backgroundShareNotifier: BackgroundShareNotifier,
    private val executionCoordinator: QueueExecutionCoordinator,
    private val operations: JobOperationRegistry,
) {
    private val schedulingMutex = Mutex()

    companion object {
        internal const val QUEUED_JOB_ID_INPUT = "jobId"
        internal const val QUEUED_JOB_DISPLAY_NAME_INPUT = "displayName"
        internal const val SPEAKER_LABEL_PREFIX_INPUT = "speakerLabelPrefix"
    }

    suspend fun enqueueSharedAudio(
        uris: List<Uri>,
        provider: TranscriptionProvider,
        customModelName: String,
        speakerDiarizationEnabled: Boolean,
        preferImmediateBackgroundStart: Boolean = false,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec? = null,
        onJobsCommitted: () -> Unit = {},
    ): QueuedShareResult {
        repository.requireQueueCapacity(uris.size)
        return queuedAudioStore.withPreparedAudio(uris) { intake ->
            enqueuePreparedSharedAudio(
                intake,
                provider,
                customModelName,
                speakerDiarizationEnabled,
                preferImmediateBackgroundStart,
                postTranscriptionWorkSpec,
                onJobsCommitted,
            )
        }
    }

    private suspend fun enqueuePreparedSharedAudio(
        intake: SharedAudioIntake,
        provider: TranscriptionProvider,
        customModelName: String,
        speakerDiarizationEnabled: Boolean,
        preferImmediateBackgroundStart: Boolean = false,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec? = null,
        onJobsCommitted: () -> Unit = {},
    ): QueuedShareResult {
        val preparedAudio = intake.preparedAudio
        if (preparedAudio.isEmpty()) {
            return QueuedShareResult(
                jobsCreated = 0,
                statusMessage = context.getString(R.string.main_waiting_for_audios),
            )
        }

        val batchId = UUID.randomUUID().toString()
        val requestedModel = resolveRequestedModel(provider, customModelName)
        val executionBackend = resolveShareExecutionBackend(preferImmediateBackgroundStart)
        val persistedPostTranscriptionConfig = postTranscriptionWorkSpec?.toPersistedConfig()
            ?: PersistedPostTranscriptionConfig(
                summarizeAfterTranscription = false,
                notifyResult = false,
                queuedSummarizerProviderName = "",
                queuedSummarizerCustomModelName = "",
            )

        val jobs = preparedAudio.map { prepared ->
            TranscriptionJob(
                uri = prepared.originalUri.toString(),
                displayName = prepared.displayName,
                sourceMimeType = prepared.mimeType,
                stagedFilePath = prepared.stagedFile.absolutePath,
                providerName = provider.name,
                requestedModel = requestedModel,
                executionBackend = executionBackend.name,
                summarizeAfterTranscription = persistedPostTranscriptionConfig.summarizeAfterTranscription,
                notifyResult = persistedPostTranscriptionConfig.notifyResult,
                queuedSummarizerProviderName = persistedPostTranscriptionConfig.queuedSummarizerProviderName,
                queuedSummarizerCustomModelName =
                persistedPostTranscriptionConfig.queuedSummarizerCustomModelName,
                batchId = batchId,
                speakerDiarizationEnabled = speakerDiarizationEnabled,
                status = JOB_STATUS_QUEUED,
                progress = 0f,
                isRunning = false,
            )
        }
        // Finish the short database handoff even if the receiving Activity stops.
        // Ownership must move before crossing a cancellable suspension boundary.
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val jobIds = repository.addJobsWithinCapacity(jobs)
            intake.transferOwnership()
            // The caller may still observe cancellation after this point, but the jobs exist.
            onJobsCommitted()
            preparedAudio.zip(jobIds).forEach { (prepared, rawJobId) ->
                try {
                    backgroundShareNotifier.showBackgroundProcessing(
                        jobId = rawJobId.toInt(),
                        displayName = prepared.displayName,
                        message = context.getString(R.string.background_share_waiting_to_start_notification_body),
                    )
                } catch (_: RuntimeException) {
                    // Notification failures must not prevent scheduling committed jobs.
                }
            }
            scheduleNextQueuedJobIfIdle()
            QueuedShareResult(
                jobsCreated = preparedAudio.size,
                statusMessage = context.resources.getQuantityString(
                    R.plurals.main_queued_files,
                    preparedAudio.size,
                    preparedAudio.size,
                ),
                firstCreatedJobId = jobIds.firstOrNull()?.toInt(),
            )
        }
    }

    suspend fun cancelJob(jobId: Int) {
        val job = repository.getJob(jobId)
        repository.cancelJob(jobId, context.getString(R.string.job_cancelled_error))
        operations.cancel(jobId)
        cancelScheduledExecution(job)
        backgroundShareNotifier.cancelResult(jobId)
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        scheduleNextQueuedJobIfIdle()
    }

    suspend fun clearCompletedJobs() {
        repository.clearCompletedJobs().forEach { completedJob ->
            backgroundShareNotifier.cancelResult(completedJob.jobId)
            backgroundShareNotifier.cancelRetryScheduled(completedJob.jobId)
            backgroundShareNotifier.cancelBackgroundProcessing(completedJob.jobId)
            completedJob.stagedFilePath?.let(queuedAudioStore::deleteStagedFile)
        }
    }

    suspend fun deleteJob(jobId: Int) {
        val job = repository.getJob(jobId)
        repository.invalidateOwners(jobId)
        operations.cancel(jobId)
        cancelScheduledExecution(job)
        backgroundShareNotifier.cancelResult(jobId)
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        queuedAudioStore.deleteStagedFile(repository.deleteJob(jobId))
        scheduleNextQueuedJobIfIdle()
    }

    suspend fun cancelRemovedHistoryJobs(jobs: List<HistoryJobCleanup>) {
        jobs.forEach { job ->
            operations.cancel(job.jobId)
            cancelScheduledExecution(job.jobId)
            backgroundShareNotifier.cancelResult(job.jobId)
            backgroundShareNotifier.cancelRetryScheduled(job.jobId)
            backgroundShareNotifier.cancelBackgroundProcessing(job.jobId)
            queuedAudioStore.deleteStagedFile(job.stagedFilePath)
        }
        scheduleNextQueuedJobIfIdle()
    }

    suspend fun clearJobTranscription(jobId: Int) = clearJobContent(jobId, HistoryContentField.TRANSCRIPT)

    suspend fun clearJobContent(jobId: Int, field: HistoryContentField) = operations.withClaimLock {
        val job = repository.getJob(jobId) ?: return@withClaimLock
        if (repository.clearJobContent(jobId, field)) {
            cancelRemovedHistoryJobs(listOf(HistoryJobCleanup(jobId, job.stagedFilePath)))
        }
    }

    suspend fun clearHistoryContent(historyId: Int, field: HistoryContentField) = operations.withClaimLock {
        cancelRemovedHistoryJobs(historyRepository.clearContent(historyId, field))
    }

    suspend fun retryJob(jobId: Int): RetryResult {
        executionCoordinator.awaitRecovery()
        val snapshot = settings.getTranscriptionQueueSettings()
        val persistedConfig = resolveAutomaticSummarizationWorkSpec()?.toPersistedConfig()
            ?: PersistedPostTranscriptionConfig(false, false, "", "")
        val result = schedulingMutex.withLock {
            val retry = repository.retryJob(
                jobId = jobId,
                providerName = snapshot.provider.name,
                requestedModel = resolveRequestedModel(snapshot.provider, snapshot.customModelName),
                executionBackend = JobExecutionBackend.WORK_MANAGER.name,
                summarizeAfterTranscription = persistedConfig.summarizeAfterTranscription,
                notifyResult = persistedConfig.notifyResult,
                queuedSummarizerProviderName = persistedConfig.queuedSummarizerProviderName,
                queuedSummarizerCustomModelName = persistedConfig.queuedSummarizerCustomModelName,
                speakerDiarizationEnabled = snapshot.speakerDiarizationEnabled,
            )
            if (retry == RetryResult.ACCEPTED) {
                operations.cancel(jobId)
                scheduler.cancel(jobId)
            }
            retry
        }
        if (result == RetryResult.ACCEPTED) {
            backgroundShareNotifier.cancelResult(jobId)
            backgroundShareNotifier.cancelRetryScheduled(jobId)
            backgroundShareNotifier.cancelBackgroundProcessing(jobId)
            scheduleNextQueuedJobIfIdle()
        }
        return result
    }

    private fun resolveRequestedModel(provider: TranscriptionProvider, customModelName: String): String? =
        when (provider) {
            TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
            TranscriptionProvider.GEMINI_FLASH_LITE,
            TranscriptionProvider.GEMINI_FLASH,
            TranscriptionProvider.GEMINI_PRO,
            -> provider.modelName
            TranscriptionProvider.GEMINI_CUSTOM -> customModelName.ifBlank { null }
        }

    suspend fun resumePendingQueue() {
        scheduleNextQueuedJobIfIdle()
    }

    suspend fun recoverAndMaintainQueue() {
        executionCoordinator.awaitRecovery()
        val expiredCards = repository.deleteExpiredTerminalCards()
        expiredCards.forEach { expired ->
            backgroundShareNotifier.cancelResult(expired.jobId)
            queuedAudioStore.deleteStagedFile(expired.stagedFilePath)
        }

        val referencedPaths = repository.getReferencedStagedFilePaths()
        val cleanup = queuedAudioStore.cleanupManagedFiles(referencedPaths)
        android.util.Log.i(
            "QueueMaintenance",
            "Queue maintenance removed ${expiredCards.size} expired terminal cards, " +
                "${cleanup.tempFilesDeleted} temporary and " +
                "${cleanup.orphanFilesDeleted} orphaned staged files.",
        )
        scheduleNextQueuedJobIfIdle()
    }

    suspend fun cancelBackgroundSummaryIfRunning(jobId: Int): Boolean {
        val job = repository.getJob(jobId) ?: return false
        if (!job.isRunning || !job.isSummarizing) return false
        repository.cancelJob(jobId, context.getString(R.string.job_cancelled_error))
        operations.cancel(jobId)
        cancelScheduledExecution(job)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        scheduleNextQueuedJobIfIdle()
        return true
    }

    suspend fun onForegroundPromotionDenied(jobId: Int): Boolean {
        executionCoordinator.awaitRecovery()
        val job = repository.getJob(jobId) ?: return false
        if (scheduler.supportsUserInitiatedJobs) {
            repository.updateExecutionBackend(jobId, JobExecutionBackend.USER_INITIATED_JOB.name)
            val userInitiatedJob = job.copy(executionBackend = JobExecutionBackend.USER_INITIATED_JOB.name)
            if (scheduler.enqueueUserInitiatedJob(userInitiatedJob)) {
                backgroundShareNotifier.showBackgroundProcessing(
                    jobId,
                    job.displayName,
                    context.getString(R.string.transcription_foreground_denied_switching_backend),
                )
                return true
            }
        }

        failBackgroundStart(jobId)
        return false
    }

    suspend fun failBackgroundStart(jobId: Int) {
        executionCoordinator.awaitRecovery()
        val job = repository.getJob(jobId) ?: return
        if (!repository.failUnstartedJob(
                jobId,
                context.getString(R.string.transcription_foreground_denied_reopen),
            )
        ) {
            return
        }
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        backgroundShareNotifier.showFailure(
            jobId = jobId,
            displayName = job.displayName,
            message = context.getString(R.string.transcription_foreground_denied_reopen),
        )
        scheduleNextQueuedJobIfIdle()
    }

    private suspend fun resolveAutomaticSummarizationWorkSpec(): PostTranscriptionWorkSpec? {
        if (!settings.automaticSummarizationFlow.first()) return null

        val keyChars = settings.getGeminiApiKey() ?: return null
        val hasGeminiKey = try {
            keyChars.isNotEmpty()
        } finally {
            keyChars.fill('\u0000')
        }
        if (!hasGeminiKey) return null

        return PostTranscriptionWorkSpec(
            summarizeAfterTranscription = true,
            notifyResult = false,
            summarizationSettings = settings.getSummarizationSettings(),
        )
    }

    suspend fun scheduleNextQueuedJobIfIdle(): Boolean {
        executionCoordinator.awaitRecovery()
        return schedulingMutex.withLock {
            if (repository.countBlockingJobs() > 0) return@withLock false
            val queuedJob = repository.getOldestQueuedJob() ?: return@withLock false
            if (scheduler.hasActiveEntry(queuedJob)) return@withLock true
            scheduleExecutionForJob(queuedJob)
        }
    }

    suspend fun onExecutionFinished(jobId: Int) {
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        scheduleNextQueuedJobIfIdle()
    }

    private suspend fun scheduleExecutionForJob(job: TranscriptionJob): Boolean {
        val backend = job.executionBackendOrDefault()
        if (backend == JobExecutionBackend.USER_INITIATED_JOB && !scheduler.supportsUserInitiatedJobs) {
            repository.updateExecutionBackend(job.id, JobExecutionBackend.WORK_MANAGER.name)
            return scheduler.enqueueWorker(job.copy(executionBackend = JobExecutionBackend.WORK_MANAGER.name))
        }

        return when (backend) {
            JobExecutionBackend.WORK_MANAGER -> scheduler.enqueueWorker(job)
            JobExecutionBackend.USER_INITIATED_JOB -> {
                if (scheduler.enqueueUserInitiatedJob(job)) {
                    true
                } else {
                    repository.updateExecutionBackend(job.id, JobExecutionBackend.WORK_MANAGER.name)
                    scheduler.enqueueWorker(job.copy(executionBackend = JobExecutionBackend.WORK_MANAGER.name))
                }
            }
        }
    }

    private suspend fun cancelScheduledExecution(job: TranscriptionJob?) {
        if (job != null) scheduler.cancel(job.id)
    }

    private suspend fun cancelScheduledExecution(jobId: Int) = scheduler.cancel(jobId)
}
