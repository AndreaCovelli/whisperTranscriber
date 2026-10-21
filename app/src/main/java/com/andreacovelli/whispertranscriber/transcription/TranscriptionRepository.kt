package com.andreacovelli.whispertranscriber.transcription

import androidx.room.withTransaction
import com.andreacovelli.whispertranscriber.QuantityText
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.StoredMessages
import com.andreacovelli.whispertranscriber.UserFacingIllegalStateException
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.HistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionHistoryDao
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.data.TranscriptionJobDao
import com.andreacovelli.whispertranscriber.queue.MAX_NONTERMINAL_JOBS
import com.andreacovelli.whispertranscriber.queue.RetryResult
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.decodeTranscriptionProvider
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

data class CompletedJobCleanup(val jobId: Int, val stagedFilePath: String?)

private const val TERMINAL_CARD_RETENTION_DAYS = 30L

private fun checkQueueCapacity(requestedNonterminalJobs: Int, maximumNonterminalJobs: Int) {
    if (requestedNonterminalJobs > maximumNonterminalJobs) {
        throw UserFacingIllegalStateException(
            UserMessage(
                R.string.queue_capacity_exceeded,
                listOf(QuantityText(R.plurals.unfinished_job_count, maximumNonterminalJobs)),
            ),
        )
    }
}

/** Details of the transcribed recording stored with its history entry; 0 or "" when unknown. */

/** What the runner measured about a finished recording; the provider comes from the job row. */
data class RecordingDetails(val durationMs: Long = 0, val sourceSizeBytes: Long = 0)

@Singleton
// This transactional facade intentionally centralizes queue, ownership, and linked-history invariants.
@Suppress("TooManyFunctions")
class TranscriptionRepository @Inject constructor(
    private val db: AppDatabase,
    private val jobDao: TranscriptionJobDao,
    private val historyDao: TranscriptionHistoryDao,
    private val historyRepository: HistoryRepository,
) {
    val jobsState: Flow<List<TranscriptionJob>> = jobDao.getAllJobs()

    suspend fun addJob(job: TranscriptionJob): Long = jobDao.insert(job)

    suspend fun requireQueueCapacity(additionalJobs: Int, maximumNonterminalJobs: Int = MAX_NONTERMINAL_JOBS) {
        require(additionalJobs >= 0)
        checkQueueCapacity(jobDao.countNonterminalJobs() + additionalJobs, maximumNonterminalJobs)
    }

    suspend fun addJobsWithinCapacity(
        jobs: List<TranscriptionJob>,
        maximumNonterminalJobs: Int = MAX_NONTERMINAL_JOBS,
    ): List<Long> = db.withTransaction {
        val currentCount = jobDao.countNonterminalJobs()
        checkQueueCapacity(currentCount + jobs.size, maximumNonterminalJobs)
        jobDao.insertAll(jobs)
    }

    /**
     * Applies the current auto-summary choice to jobs whose transcription has not finished, so the
     * runner reads it when they complete. A null [settings] turns automatic summaries off for them.
     */
    suspend fun applyQueuedAutoSummary(settings: SummarizationSettings?) {
        jobDao.updateQueuedAutoSummary(
            summarizeAfterTranscription = settings != null,
            queuedSummarizerProviderName = settings?.provider?.name.orEmpty(),
            queuedSummarizerCustomModelName = settings?.customModelName.orEmpty(),
            queuedSummaryStyle = settings?.style?.name.orEmpty(),
            queuedSummaryCustomPrompt = settings?.customPrompt.orEmpty(),
        )
    }

    suspend fun updateStatusAndProgress(id: Int, ownerId: String, status: UserMessage, progress: Float): Boolean =
        jobDao.updateStatusAndProgress(id, ownerId, StoredMessages.encode(status), progress) > 0

    suspend fun persistCompletedTranscription(
        id: Int,
        ownerId: String,
        displayName: String,
        data: TranscriptionData,
        recording: RecordingDetails = RecordingDetails(),
    ): Boolean = db.withTransaction {
        val currentJob = jobDao.getJobById(id)
        if (currentJob?.executionOwnerId != ownerId || !currentJob.isRunning) {
            return@withTransaction false
        }
        val normalizedLanguage = data.language.ifBlank { "Unknown" }
        val historyId = historyRepository.insert(
            TranscriptionHistory(
                text = data.text,
                language = normalizedLanguage,
                model = data.model,
                displayName = displayName,
                providerName = decodeTranscriptionProvider(currentJob.providerName).name,
                durationMs = recording.durationMs,
                sourceSizeBytes = recording.sourceSizeBytes,
                speakerLabels = data.speakerLabels,
            ),
        )
        jobDao.updateResult(id = id, ownerId = ownerId, historyId = historyId) > 0
    }

    suspend fun updateError(id: Int, ownerId: String, error: UserMessage, status: String): Boolean =
        jobDao.updateError(id, ownerId, StoredMessages.encode(error), status, System.currentTimeMillis()) > 0

    suspend fun markRetryScheduled(id: Int, ownerId: String): Boolean = jobDao.markRetryScheduled(id, ownerId) > 0

    suspend fun updateStatus(id: Int, ownerId: String, status: UserMessage): Boolean =
        jobDao.updateStatus(id, ownerId, StoredMessages.encode(status)) > 0

    suspend fun updateExecutionBackend(id: Int, executionBackend: String) {
        jobDao.updateExecutionBackend(id, executionBackend)
    }

    suspend fun failUnstartedJob(id: Int, message: UserMessage): Boolean =
        jobDao.failUnstartedJob(id, StoredMessages.encode(message), System.currentTimeMillis()) > 0

    suspend fun clearStagedFilePath(id: Int, ownerId: String): Boolean = jobDao.clearStagedFilePath(id, ownerId) > 0

    suspend fun resetRunningToQueued(id: Int, ownerId: String): Boolean = jobDao.resetRunningToQueued(id, ownerId) > 0

    suspend fun markRunningAsCompleted(id: Int, ownerId: String): Boolean =
        jobDao.markRunningAsCompleted(id, ownerId, System.currentTimeMillis()) > 0

    suspend fun getRecoverableJobs(): List<TranscriptionJob> = jobDao.getRecoverableJobs()

    suspend fun recoverPreviousSession(sessionPrefix: String) = db.withTransaction {
        jobDao.getRecoverableJobs().forEach { job ->
            if (!job.executionOwnerId.startsWith(sessionPrefix)) {
                jobDao.requeueOrphanedExecution(job.id, job.executionOwnerId)
            }
        }
        jobDao.cancelOrphanedUiSummaries(sessionPrefix)
    }

    suspend fun requeueOrphanedExecution(id: Int, ownerId: String): Boolean =
        jobDao.requeueOrphanedExecution(id, ownerId) > 0

    suspend fun clearJobTranscription(jobId: Int): Boolean = clearJobContent(jobId, HistoryContentField.TRANSCRIPT)

    suspend fun clearJobContent(jobId: Int, field: HistoryContentField): Boolean = db.withTransaction {
        val job = jobDao.getJobById(jobId) ?: return@withTransaction false
        if (job.historyId != null) {
            historyRepository.clearContent(job.historyId, field)
        } else {
            when (field) {
                HistoryContentField.TRANSCRIPT -> jobDao.clearTranscriptionResult(jobId, System.currentTimeMillis())
                HistoryContentField.SUMMARY -> jobDao.clearSummaryResult(jobId, System.currentTimeMillis())
            }
        }
        true
    }

    /** Reads content from the same owner [clearJobContent] writes to: the linked history row, else the job. */
    suspend fun getJobContent(jobId: Int, field: HistoryContentField): String = db.withTransaction {
        val job = jobDao.getJobById(jobId) ?: return@withTransaction ""
        val history = job.historyId?.let { historyDao.getById(it) }
        when (field) {
            HistoryContentField.TRANSCRIPT -> history?.text ?: job.transcript
            HistoryContentField.SUMMARY -> history?.summary ?: job.summary
        }
    }

    suspend fun getJob(jobId: Int): TranscriptionJob? = jobDao.getJobById(jobId)

    suspend fun getTranscriptForJob(jobId: Int): String = historyDao.getByJobId(jobId)?.text.orEmpty()

    suspend fun getOldestQueuedJob(): TranscriptionJob? = jobDao.getOldestQueuedJob()

    suspend fun countBlockingJobs(): Int = jobDao.countBlockingJobs()

    suspend fun claimJobForExecution(jobId: Int, ownerId: String, status: UserMessage): TranscriptionJob? =
        db.withTransaction {
            if (jobDao.claimJobForExecution(jobId, ownerId, StoredMessages.encode(status)) > 0) {
                jobDao.getJobById(jobId)
            } else {
                null
            }
        }

    suspend fun retryJob(
        jobId: Int,
        providerName: String,
        requestedModel: String?,
        executionBackend: String,
        summarizeAfterTranscription: Boolean,
        notifyResult: Boolean,
        queuedSummarizerProviderName: String,
        queuedSummarizerCustomModelName: String,
        speakerDiarizationEnabled: Boolean = false,
        hints: TranscriptionHints = TranscriptionHints(),
        queuedSummaryStyle: String = "",
        queuedSummaryCustomPrompt: String = "",
    ): RetryResult = db.withTransaction {
        val job = jobDao.getJobById(jobId) ?: return@withTransaction RetryResult.INELIGIBLE_STATUS
        if (job.status !in setOf(JOB_STATUS_ERROR, JOB_STATUS_CANCELLED) || job.isRunning || job.isSummarizing) {
            return@withTransaction RetryResult.INELIGIBLE_STATUS
        }
        val audio = job.stagedFilePath?.takeIf(String::isNotBlank)?.let(::File)
        if (audio == null || !audio.isFile || !audio.canRead() || audio.length() == 0L) {
            return@withTransaction RetryResult.UNAVAILABLE_AUDIO
        }
        if (jobDao.countNonterminalJobs() >= MAX_NONTERMINAL_JOBS) {
            return@withTransaction RetryResult.QUEUE_FULL
        }
        jobDao.retryById(
            jobId = jobId,
            providerName = providerName,
            requestedModel = requestedModel,
            executionBackend = executionBackend,
            summarizeAfterTranscription = summarizeAfterTranscription,
            notifyResult = notifyResult,
            queuedSummarizerProviderName = queuedSummarizerProviderName,
            queuedSummarizerCustomModelName = queuedSummarizerCustomModelName,
            speakerDiarizationEnabled = speakerDiarizationEnabled,
            languageHint = hints.languageCode,
            vocabulary = encodeVocabulary(hints.vocabulary),
        )
        jobDao.updateQueuedSummaryStyle(jobId, queuedSummaryStyle, queuedSummaryCustomPrompt)
        RetryResult.ACCEPTED
    }

    suspend fun cancelJob(jobId: Int, message: UserMessage): Boolean =
        jobDao.cancelJob(jobId, StoredMessages.encode(message), System.currentTimeMillis()) > 0

    suspend fun invalidateOwners(jobId: Int): Boolean = jobDao.invalidateOwners(jobId) > 0

    suspend fun clearCompletedJobs(): List<CompletedJobCleanup> = db.withTransaction {
        val completedJobs = jobDao.getCompletedJobs()
        if (completedJobs.isEmpty()) return@withTransaction emptyList()
        jobDao.deleteCompletedByIds(completedJobs.map(TranscriptionJob::id))
        completedJobs.map { job ->
            CompletedJobCleanup(jobId = job.id, stagedFilePath = job.stagedFilePath)
        }
    }

    suspend fun deleteJob(jobId: Int): String? = db.withTransaction {
        val job = jobDao.getJobById(jobId) ?: return@withTransaction null
        jobDao.invalidateOwners(jobId)
        jobDao.deleteById(jobId)
        job.stagedFilePath
    }

    suspend fun deleteExpiredTerminalCards(nowMillis: Long = System.currentTimeMillis()): List<CompletedJobCleanup> =
        db.withTransaction {
            val cutoff = nowMillis - TimeUnit.DAYS.toMillis(TERMINAL_CARD_RETENTION_DAYS)
            val expired = jobDao.getExpiredTerminalJobs(cutoff)
            jobDao.deleteExpiredTerminalJobs(cutoff)
            expired.map { job -> CompletedJobCleanup(job.id, job.stagedFilePath) }
        }

    suspend fun getReferencedStagedFilePaths(): Set<String> =
        jobDao.getReferencedStagedFilePaths().filter(String::isNotBlank).toSet()
}
